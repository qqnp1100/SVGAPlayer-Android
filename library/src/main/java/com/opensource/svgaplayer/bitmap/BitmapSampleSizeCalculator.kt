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

        // Raw height and width of image
        val height = (options.outHeight * scaleY).toInt()
        val width = (options.outWidth * scaleX).toInt()
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
}