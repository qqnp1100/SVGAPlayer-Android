package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.File

/**
 * 通过文件解码 Bitmap
 *
 * Create by im_dsd 2020/7/7 17:50
 */
internal object SVGABitmapFileDecoder : SVGABitmapDecoder<String>() {

    override fun onDecode(data: String, ops: BitmapFactory.Options): Bitmap? {
        return BitmapFactory.decodeFile(data, ops)
    }

    override fun onDecodeWithImageDecoder(
        data: String,
        scaleX: Float,
        scaleY: Float,
        reqWidth: Int,
        reqHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Bitmap? {
        val file = File(data)
        if (!file.isFile) {
            return null
        }
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                BitmapSampleSizeCalculator.calculateTargetSize(
                    info.size.width,
                    info.size.height,
                    scaleX,
                    scaleY,
                    reqWidth,
                    reqHeight,
                    videoWidth,
                    videoHeight
                )?.let { targetSize ->
                    decoder.setTargetSize(targetSize.first, targetSize.second)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
