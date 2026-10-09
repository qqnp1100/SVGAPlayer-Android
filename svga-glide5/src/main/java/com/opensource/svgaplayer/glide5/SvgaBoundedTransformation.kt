package com.opensource.svgaplayer.glide5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Old BitmapFactory decoders can round sampling up; enforce the final pixel budget as well. */
internal class SvgaBoundedTransformation(private val limit: Int) : BitmapTransformation() {
    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val edge = maxOf(toTransform.width, toTransform.height)
        if (edge <= limit) return toTransform
        val width = (toTransform.width.toDouble() * limit / edge).roundToInt().coerceAtLeast(1)
        val height = (toTransform.height.toDouble() * limit / edge).roundToInt().coerceAtLeast(1)
        return pool.get(width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(toTransform.hasAlpha())
            Canvas(this).drawBitmap(toTransform, null, RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("svga.dynamic.bound:$limit".toByteArray(Charsets.UTF_8))
    }
    override fun equals(other: Any?) = other is SvgaBoundedTransformation && limit == other.limit
    override fun hashCode() = 31 * javaClass.name.hashCode() + limit
}
