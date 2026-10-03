package com.opensource.svgaplayer.drawer

import android.graphics.Canvas
import android.widget.ImageView
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoSpriteFrameEntity
import com.opensource.svgaplayer.utils.Pools
import com.opensource.svgaplayer.utils.SVGAScaleInfo
import kotlin.math.max

/**
 * Created by cuiminghui on 2017/3/29.
 */

open internal class SGVADrawer(val videoItem: SVGAVideoEntity) {

    val scaleInfo = SVGAScaleInfo()

    private val spritePool = Pools.SimplePool<SVGADrawerSprite>(max(1, videoItem.spriteList.size))
    private val frameSprites = ArrayList<SVGADrawerSprite>(videoItem.spriteList.size)

    inner class SVGADrawerSprite(var _matteKey: String? = null, var _imageKey: String? = null, var _frameEntity: SVGAVideoSpriteFrameEntity? = null) {
        val matteKey get() = _matteKey
        val imageKey get() = _imageKey
        val frameEntity get() = _frameEntity!!
    }

    internal fun requestFrameSprites(frameIndex: Int): List<SVGADrawerSprite> {
        frameSprites.clear()
        for (sprite in videoItem.spriteList) {
            val imageKey = sprite.imageKey ?: continue
            val frame = sprite.frames.getOrNull(frameIndex) ?: continue
            if (!imageKey.endsWith(".matte") && frame.alpha <= 0.0) continue
            frameSprites.add((spritePool.acquire() ?: SVGADrawerSprite()).apply {
                _matteKey = sprite.matteKey
                _imageKey = imageKey
                _frameEntity = frame
            })
        }
        return frameSprites
    }

    internal fun releaseFrameSprites(sprites: List<SVGADrawerSprite>) {
        sprites.forEach { spritePool.release(it) }
    }

    open fun drawFrame(canvas : Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        scaleInfo.performScaleType(canvas.width.toFloat(),canvas.height.toFloat(), videoItem.videoSize.width.toFloat(), videoItem.videoSize.height.toFloat(), scaleType)
    }

}
