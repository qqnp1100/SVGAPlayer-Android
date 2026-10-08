package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.ImageView
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import kotlinx.coroutines.*

class SVGADrawable(val videoItem: SVGAVideoEntity, val dynamicItem: SVGADynamicEntity) :
    Drawable() {

    constructor(videoItem: SVGAVideoEntity) : this(videoItem, SVGADynamicEntity())

    var cleared = true
        internal set(value) {
            if (field == value) {
                return
            }
            field = value
            if (value) {
                textScrollTicker.stop()
                drawer.pauseTextScrolling()
            }
            invalidateSelf()
        }

    var currentFrame = 0
        private set

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    private val drawer = SVGACanvasDrawer(videoItem, dynamicItem)
    private var externalClock = false
    private var presentationTimeNanos = 0L
    private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingFrame: Job? = null
    private var drawnFrame = -1

    /** The modern adapter owns scheduling; scrolling text uses the same presentation timeline. */
    fun advanceExternalClock(frame: Int, timeNanos: Long) {
        externalClock = true
        textScrollTicker.stop()
        val changed = currentFrame != frame
        updateCurrentFrame(frame, false)
        presentationTimeNanos = timeNanos
        if (changed || drawer.hasActiveScrollingText) invalidateSelf()
    }

    /** Show the first frame without a playback or scrolling-text clock. */
    fun showStaticFrame() {
        advanceExternalClock(0, 0L)
        cleared = false
        setPlaybackActive(false)
        invalidateSelf()
    }

    /** Prepare text/path caches on a worker before the first visible frame. */
    fun prepare(width: Int, height: Int) {
        prepareFrame(currentFrame)
        val picture = android.graphics.Picture()
        val canvas = picture.beginRecording(width, height)
        try { drawer.renderFrame(canvas, currentFrame, scaleType, width, height, 0L) }
        finally { picture.endRecording() }
    }
    /** Prepare only this frame's deferred pixels on the caller's worker thread. */
    fun prepareFrame(frame: Int) { videoItem.deferredImages?.prepare(frame) }
    fun isFrameReady(frame: Int): Boolean = videoItem.deferredImages?.isReady(frame) != false
    internal fun prepareAllImages() { videoItem.deferredImages?.prepareAll() }
    internal fun areAllImagesReady(): Boolean = videoItem.deferredImages?.allReady() != false
    private var textScrollAttached = true
    private var textScrollVisible = true
    private val textScrollTicker = TextScrollTicker(
        nowUptimeMillis = SystemClock::uptimeMillis,
        schedule = { runnable, deadline -> scheduleSelf(runnable, deadline) },
        unschedule = { runnable -> unscheduleSelf(runnable) },
        invalidate = { invalidateSelf() },
    )

    fun updateCurrentFrame(frame: Int, invalidate: Boolean = true) {
        if (currentFrame == frame) {
            return
        }
        currentFrame = frame
        if (invalidate) {
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        if (cleared) {
            textScrollTicker.onDraw(false)
            return
        }
        val frame = currentFrame
        if (!isFrameReady(frame) && callback is android.view.View) {
            // Covers a View drawing during initial preparation or direct Drawable frame updates.
            if (pendingFrame?.isActive != true) pendingFrame = frameScope.launch {
                try {
                    withContext(Dispatchers.Default) { prepareFrame(frame) }
                    ensureActive()
                    invalidateSelf()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    cleared = true
                    android.util.Log.e("SVGADrawable", "Frame decode failed", e)
                }
            }
            if (drawnFrame < 0 || !isFrameReady(drawnFrame)) return
        } else prepareFrame(frame)
        val displayFrame = if (isFrameReady(frame)) frame else drawnFrame
        if (externalClock) drawer.renderFrame(canvas, displayFrame, scaleType, canvas.width, canvas.height, presentationTimeNanos)
        else {
            drawer.drawFrame(canvas, displayFrame, scaleType)
            textScrollTicker.onDraw(drawer.hasActiveScrollingText)
        }
        drawnFrame = displayFrame
        videoItem.deferredImages?.afterDraw(displayFrame, canvas, callback as? android.view.View)
    }

    internal fun setTextScrollEnabled(enabled: Boolean) {
        drawer.pauseTextScrolling()
        if (enabled && !cleared) {
            textScrollTicker.setEnabled(true)
        } else {
            textScrollTicker.stop()
        }
    }

    /** Used by external clocks to stop scrolling work while paused or off screen. */
    fun setPlaybackActive(active: Boolean) {
        if (externalClock) { if (!active) drawer.pauseTextScrolling() }
        else setTextScrollEnabled(active)
    }

    internal fun setTextScrollAttached(attached: Boolean) {
        textScrollAttached = attached
        if (!attached) {
            drawer.pauseTextScrolling()
        }
        updateTextScrollHostActive()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        textScrollVisible = visible
        if (!visible) {
            drawer.pauseTextScrolling()
        }
        updateTextScrollHostActive()
        return changed
    }

    private fun updateTextScrollHostActive() {
        textScrollTicker.setHostActive(textScrollAttached && textScrollVisible)
    }

    override fun setAlpha(alpha: Int) {

    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {

    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.resume or SvgaState.resume; custom renderers own a SvgaAudioSession.")
    fun resume() {
        videoItem.audioList.forEach { audio ->
            audio.playIDs?.map {
                if (SVGASoundManager.isInit()) {
                    SVGASoundManager.resume(it)
                } else {
                    videoItem.soundPool?.resume(it)
                }
            }
        }
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.pause or SvgaState.pause; custom renderers own a SvgaAudioSession.")
    fun pause() {
        videoItem.audioList.forEach { audio ->
            audio.playIDs?.map {
                if (SVGASoundManager.isInit()) {
                    SVGASoundManager.pause(it)
                } else {
                    videoItem.soundPool?.pause(it)
                }
            }
        }
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.cancel or clearSvga; custom renderers close their SvgaAudioSession.")
    fun stop() {
        videoItem.audioList.forEach { audio ->
            audio.playIDs?.map {
                if (SVGASoundManager.isInit()) {
                    SVGASoundManager.stop(it)
                } else {
                    videoItem.soundPool?.stop(it)
                }
            }
        }
    }

    fun clear() {
        frameScope.cancel()
        textScrollTicker.stop()
        drawer.pauseTextScrolling()
        drawer.clearCaches()
        videoItem.audioList.forEach { audio ->
            audio.playIDs?.map {
                if (SVGASoundManager.isInit()) {
                    SVGASoundManager.stop(it)
                } else {
                    videoItem.soundPool?.stop(it)
                }
            }
            audio.playIDs = null
        }
        videoItem.clear()
        dynamicItem.clearDynamicObjects()
    }
}
