package com.opensource.svgaplayer.bitmap

import android.graphics.BitmapFactory
import android.util.Log

/**
 *
 * Create by im_dsd 2020/7/7 17:59
 */
internal object BitmapSampleSizeCalculator {

    fun calculate(
        options: BitmapFactory.Options,
        scaleX: Float,
        scaleY: Float,
        reqWidth: Int,
        reqHeight: Int,
        svgaWidth: Int,
        svgaHeight: Int
    ): Int {
        return calculate(
            options.outWidth,
            options.outHeight,
            scaleX,
            scaleY,
            reqWidth,
            reqHeight,
            svgaWidth,
            svgaHeight
        )
    }

    fun calculate(
        outWidth: Int,
        outHeight: Int,
        scaleX: Float,
        scaleY: Float,
        reqWidth: Int,
        reqHeight: Int,
        svgaWidth: Int,
        svgaHeight: Int
    ): Int {
        // Raw height and width of image
        val height = (outHeight * scaleY).toInt()
        val width = (outWidth * scaleX).toInt()
        var inSampleSize = 1

        if (reqHeight <= 0 || reqWidth <= 0) {
            return inSampleSize
        }
        if (svgaWidth <= 0 || svgaHeight <= 0) {
            return inSampleSize
        }

        val targetWidth = Math.max(width * reqWidth / svgaWidth, 1)
        val targetHeight = Math.max(height * reqHeight / svgaHeight, 1)


        if (height > targetHeight || width > targetWidth) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= targetHeight && halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun calculateTargetSize(
        outWidth: Int,
        outHeight: Int,
        scaleX: Float,
        scaleY: Float,
        reqWidth: Int,
        reqHeight: Int,
        svgaWidth: Int,
        svgaHeight: Int
    ): Pair<Int, Int>? {
        if (outWidth <= 0 || outHeight <= 0) {
            return null
        }
        if (reqHeight <= 0 || reqWidth <= 0) {
            return null
        }
        if (svgaWidth <= 0 || svgaHeight <= 0) {
            return null
        }
        val displayWidth = outWidth * scaleX
        val displayHeight = outHeight * scaleY
        val targetWidth = Math.max((displayWidth * reqWidth / svgaWidth).toInt(), 1)
        val targetHeight = Math.max((displayHeight * reqHeight / svgaHeight).toInt(), 1)
        val targetScale = Math.max(
            targetWidth.toFloat() / outWidth.toFloat(),
            targetHeight.toFloat() / outHeight.toFloat()
        ).coerceAtMost(1f)
        if (targetScale >= 1f) {
            return null
        }
        return Pair(
            Math.max((outWidth * targetScale).toInt(), 1),
            Math.max((outHeight * targetScale).toInt(), 1)
        )
    }
}
