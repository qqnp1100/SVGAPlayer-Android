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
    private var prepared = false
    private var playing = false
    private var closed = false
    private var reverse = false
    private val rect = Rect()
    private val checkVisible = Runnable { schedule() }

    fun start(range: SVGARange?, reverse: Boolean) {
        pause()
        this.reverse = reverse
        val start = (range?.location ?: 0).coerceIn(0, resource.frames - 1)
        val end = (start.toLong() + (range?.length ?: resource.frames).toLong() - 1)
            .coerceIn(start.toLong(), (resource.frames - 1).toLong()).toInt()
        clock = SvgaPlayback(resource.frames, resource.fps, start, end, reverse)
        resume()
    }
    fun seek(frame: Int, andPlay: Boolean) {
        pause()
        reverse = false
        clock = SvgaPlayback(resource.frames, resource.fps)
        clock.seekFrame(frame)
        drawable.cleared = false
        drawable.advanceExternalClock(clock.frame, clock.positionNanos)
        if (andPlay) resume()
    }
    fun resume() {
        if (closed) return
        playing = true; view.isAnimating = true
        drawable.cleared = false
        if (prepared) { schedule(); return }
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            val sound = SvgaAudioSession(view.context, resource)
            try {
                while (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) delay(16)
                drawable.dynamicItem.requestDynamicImage(view)
                val width = view.width; val height = view.height
                drawable.scaleType = view.scaleType
                withContext(Dispatchers.Default) { drawable.prepare(width, height) }
                withContext(Dispatchers.IO) { sound.prepare() }
                ensureActive()
                audio = sound
                prepared = true
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
    override fun doFrame(frameTimeNanos: Long) {
        if (!playing || closed) return
        if (!view.isAttachedToWindow ||
            view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false ||
            (view.pauseWhenHide && (!view.isShown || !view.getGlobalVisibleRect(rect)))) {
            clock.pause(); audio?.pause(); drawable.setPlaybackActive(false)
            view.postDelayed(checkVisible, 200)
            return
        }
        drawable.scaleType = view.scaleType
        drawable.setPlaybackActive(true)
        val before = clock.completedIterations
        val done = clock.tick(frameTimeNanos, view.loops.coerceAtLeast(0))
        drawable.advanceExternalClock(clock.frame, clock.positionNanos)
        audio?.advance(clock.frame, clock.completedIterations, reverse = reverse)
        view.callback?.onStep(clock.frame, (clock.frame + 1).toDouble() / resource.frames)
        if (closed || !playing) return
        if (done) {
            pause()
            when (view.fillMode) {
                SVGAImageView.FillMode.Backward -> drawable.updateCurrentFrame(clock.startFrame)
                SVGAImageView.FillMode.Forward -> drawable.updateCurrentFrame(clock.endFrame)
                SVGAImageView.FillMode.Clear -> drawable.cleared = true
            }
            view.callback?.onFinished()
        } else {
            if (clock.completedIterations > before) view.callback?.onRepeat()
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
        pause(); closed = true; scope.cancel(); audio?.close(); audio = null
    }
}
