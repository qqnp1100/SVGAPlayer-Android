package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import com.opensource.svgaplayer.SvgaDecodeOptions
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal object SvgaImageDecoder {
    fun decode(file: File?, bytes: ByteArray?, sourceWidth: Int, sourceHeight: Int,
               width: Int, height: Int, options: SvgaDecodeOptions): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            val source = if (file != null) ImageDecoder.createSource(file)
                else ImageDecoder.createSource(ByteBuffer.wrap(requireNotNull(bytes)))
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.memorySizePolicy = if (options.bitmapConfig == Bitmap.Config.RGB_565)
                    ImageDecoder.MEMORY_POLICY_LOW_RAM else ImageDecoder.MEMORY_POLICY_DEFAULT
                decoder.setTargetSize(width, height)
            }
        } else {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = options.bitmapConfig
                inSampleSize = 1
                while (sourceWidth / (inSampleSize * 2) >= width && sourceHeight / (inSampleSize * 2) >= height) {
                    inSampleSize *= 2
                }
                // Native density scaling avoids an application-side full-size bitmap + resized copy.
                val scale = maxOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight) * inSampleSize
                inScaled = scale < 1.0
                inDensity = 1_000_000
                inTargetDensity = (inDensity * scale).roundToInt().coerceAtLeast(1)
            }
            if (file != null) BitmapFactory.decodeFile(file.path, opts)
            else requireNotNull(bytes).let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
        }
        // Rendering uses an explicit matrix; synthetic density must never scale pixels again.
        bitmap?.density = Bitmap.DENSITY_NONE
        return bitmap
    }
}
