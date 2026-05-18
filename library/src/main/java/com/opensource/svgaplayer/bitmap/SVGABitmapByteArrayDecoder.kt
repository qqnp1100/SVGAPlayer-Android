package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

/**
 * 通过字节码解码 Bitmap
 *
 * Create by im_dsd 2020/7/7 17:50
 */
internal object SVGABitmapByteArrayDecoder : SVGABitmapDecoder<ByteArray>() {

    override fun onDecode(data: ByteArray, ops: BitmapFactory.Options): Bitmap? {
        return BitmapFactory.decodeByteArray(data, 0, data.count(), ops)
    }

    override fun onDecodeWithImageDecoder(
        data: ByteArray,
        scaleX: Float,
        scaleY: Float,
        reqWidth: Int,
        reqHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Bitmap? {
        if (data.isEmpty()) {
            return null
        }
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
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
