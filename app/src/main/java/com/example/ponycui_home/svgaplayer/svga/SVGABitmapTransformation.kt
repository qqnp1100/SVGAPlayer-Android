package com.example.ponycui_home.svgaplayer.svga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.util.Util
import java.nio.ByteBuffer
import java.security.MessageDigest


class SVGABitmapTransformation(val scale: Float) : BitmapTransformation() {
    companion object {
        private val ID = "com.bumptech.glide.transformations.SVGABitmapTransformation"
        private val ID_BYTES = ID.toByteArray(charset(STRING_CHARSET_NAME))
    }


    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        if (scale >= 1f) {
            return toTransform
        }
        val scaledWidth = Math.max(toTransform.width * scale, 1f)
        val scaledHeight = Math.max(toTransform.height * scale, 1f)

        val config = if (toTransform.config != null) toTransform.config else Bitmap.Config.ARGB_8888
        val bitmap = pool.get(scaledWidth.toInt(), scaledHeight.toInt(), config)
        bitmap.setHasAlpha(true)
        val targetRect = RectF(0f, 0f, scaledWidth, scaledHeight)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(toTransform, null, targetRect, null)
        return bitmap
    }

    override fun equals(o: Any?): Boolean {
        return o is SVGABitmapTransformation && o.scale == scale
    }

    override fun hashCode(): Int {
        return Util.hashCode(ID.hashCode(), Util.hashCode(scale))
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        val radiusData = ByteBuffer.allocate(4).putFloat(scale).array()
        messageDigest.update(radiusData)
    }
}