package com.opensource.svgaplayer

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import android.graphics.Rect
import android.view.Choreographer
import com.opensource.svgaplayer.utils.SVGARange
import kotlinx.coroutines.*

/** Prepared resources keep the established View controls but use the shared frame clock. */
internal class SvgaLegacyViewSession(
    private val view: SVGAImageView,
    private val drawable: SVGADrawable,
    private val resource: SvgaResource,
) : Choreographer.FrameCallback, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clock = SvgaPlayback(resource.frames, resource.fps)
    private var audio: SvgaAudioSession? = null
    private var prepareJob: Job? = null
    private var frameJob: Job? = null
    private var pendingCompletion = false
    private var prepared = false
    private var playing = false
    private var closed = false
    private var reverse = false
    private val rect = Rect()
    private val checkVisible = Runnable { schedule() }

    fun start(range: SVGARange?, reverse: Boolean) {
        pause()
        pendingCompletion = false
        this.reverse = reverse
        val start = (range?.location ?: 0).coerceIn(0, resource.frames - 1)
        val end = (start.toLong() + (range?.length ?: resource.frames).toLong() - 1)
            .coerceIn(start.toLong(), (resource.frames - 1).toLong()).toInt()
        clock = SvgaPlayback(resource.frames, resource.fps, start, end, reverse)
        drawable.advanceExternalClock(clock.frame, clock.positionNanos)
        resume()
    }
    fun seek(frame: Int, andPlay: Boolean) {
        pause()
        pendingCompletion = false
        frameJob?.cancel(); frameJob = null
        reverse = false
        clock = SvgaPlayback(resource.frames, resource.fps)
        clock.seekFrame(frame)
        drawable.cleared = false
        val playback = clock
        if (drawable.isFrameReady(playback.frame)) drawable.advanceExternalClock(playback.frame, playback.positionNanos)
        else frameJob = scope.launch {
            try {
                withContext(Dispatchers.Default) { drawable.prepareFrame(playback.frame) }
                ensureActive()
                if (!closed && clock === playback) drawable.advanceExternalClock(playback.frame, playback.positionNanos)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pause(); android.util.Log.e("SVGAImageView", "Frame decode failed", e) }
        }
        if (andPlay) resume()
    }
    fun resume() {
        if (closed) return
        playing = true; view.isAnimating = true
        drawable.cleared = false
        if (prepared) {
            if (view.loops != 1 && !drawable.areAllImagesReady()) {
                frameJob?.cancel()
                frameJob = scope.launch {
                    try {
                        withContext(Dispatchers.Default) { drawable.prepareAllImages() }
                        ensureActive()
                        if (playing) schedule()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { pause(); android.util.Log.e("SVGAImageView", "Image decode failed", e) }
                }
            } else schedule()
            return
        }
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            val sound = SvgaAudioSession(view.context, resource)
            try {
                while (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) delay(16)
                drawable.dynamicItem.requestDynamicImage(view)
                val width = view.width; val height = view.height
                drawable.scaleType = view.scaleType
                withContext(Dispatchers.Default) {
                    if (view.loops != 1) drawable.prepareAllImages()
                    drawable.prepare(width, height)
                }
                withContext(Dispatchers.IO) { sound.prepare() }
                ensureActive()
                audio = sound
                prepared = true
                drawable.invalidateSelf()
                if (playing) schedule()
            } catch (e: CancellationException) { sound.close(); throw e }
            catch (e: Exception) { sound.close(); prepared = true; if (playing) schedule() }
        }
    }
    private fun schedule() {
        if (!closed && playing) {
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun isHostActive() = view.isAttachedToWindow &&
        view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false &&
        (!view.pauseWhenHide || (view.isShown && view.getGlobalVisibleRect(rect)))
    override fun doFrame(frameTimeNanos: Long) {
        if (!playing || closed) return
        if (!isHostActive()) {
            clock.pause(); audio?.pause(); drawable.setPlaybackActive(false)
            view.postDelayed(checkVisible, 200)
            return
        }
        drawable.scaleType = view.scaleType
        drawable.setPlaybackActive(true)
        if (frameJob?.isActive == true) { clock.pause(); audio?.pause(); schedule(); return }
        val playback = clock
        val before = clock.completedIterations
        pendingCompletion = clock.tick(frameTimeNanos, view.loops.coerceAtLeast(0)) || pendingCompletion
        if (!drawable.isFrameReady(playback.frame)) {
            playback.pause(); audio?.pause()
            frameJob = scope.launch {
                try {
                    withContext(Dispatchers.Default) { drawable.prepareFrame(playback.frame) }
                    ensureActive()
                    if (!closed && playing && clock === playback) presentFrame(playback, before)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { pause(); android.util.Log.e("SVGAImageView", "Frame decode failed", e) }
            }
        } else presentFrame(playback, before)
    }
    private fun presentFrame(playback: SvgaPlayback, before: Int) {
        // Only an active host may resume audio after deferred decoding finishes.
        if (!isHostActive()) { schedule(); return }
        drawable.advanceExternalClock(playback.frame, playback.positionNanos)
        audio?.advance(playback.frame, playback.completedIterations, reverse = reverse)
        view.callback?.onStep(playback.frame, (playback.frame + 1).toDouble() / resource.frames)
        if (closed || !playing || clock !== playback) return
        if (pendingCompletion) {
            pendingCompletion = false
            pause()
            when (view.fillMode) {
                SVGAImageView.FillMode.Backward -> drawable.updateCurrentFrame(clock.startFrame)
                SVGAImageView.FillMode.Forward -> drawable.updateCurrentFrame(clock.endFrame)
                SVGAImageView.FillMode.Clear -> drawable.cleared = true
            }
            view.callback?.onFinished()
        } else {
            if (playback.completedIterations > before) view.callback?.onRepeat()
            schedule()
        }
    }
    fun pause() {
        playing = false; view.isAnimating = false; clock.pause(); audio?.pause()
        drawable.setPlaybackActive(false)
        Choreographer.getInstance().removeFrameCallback(this)
        view.removeCallbacks(checkVisible)
    }
    override fun close() {
        if (closed) return
        pendingCompletion = false
        pause(); closed = true; scope.cancel(); audio?.close(); audio = null
    }
}
