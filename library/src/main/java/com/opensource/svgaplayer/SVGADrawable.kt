package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
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
            invalidateSelf()
        }

    var currentFrame = 0
        private set

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    private val drawer = SVGACanvasDrawer(videoItem, dynamicItem)

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
            return
        }
        canvas?.let {
            drawer.drawFrame(it, currentFrame, scaleType)
        }
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