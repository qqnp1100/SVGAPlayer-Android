package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.ImageView
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer

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
        val picture = android.graphics.Picture()
        val canvas = picture.beginRecording(width, height)
        try { drawer.renderFrame(canvas, currentFrame, scaleType, width, height, 0L) }
        finally { picture.endRecording() }
    }
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
        if (externalClock) drawer.renderFrame(canvas, currentFrame, scaleType, canvas.width, canvas.height, presentationTimeNanos)
        else {
            drawer.drawFrame(canvas, currentFrame, scaleType)
            textScrollTicker.onDraw(drawer.hasActiveScrollingText)
        }
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
