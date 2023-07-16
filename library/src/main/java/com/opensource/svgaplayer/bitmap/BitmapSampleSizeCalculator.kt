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
        reqWidth: Int,
        reqHeight: Int,
        svgaWidth: Int,
        svgaHeight: Int
    ): Int {

        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (reqHeight <= 0 || reqWidth <= 0) {
            return inSampleSize
        }
        if (svgaWidth <= 0 || svgaHeight <= 0) {
            return inSampleSize
        }

        val targetWidth = width * reqWidth / svgaWidth
        val targetHeight = height * reqHeight / svgaHeight


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