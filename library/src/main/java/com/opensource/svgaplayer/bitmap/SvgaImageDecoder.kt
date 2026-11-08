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
    private const val DENSITY = 1_000_000

    private data class FactoryScale(val sampleSize: Int, val scaled: Boolean, val targetDensity: Int) {
        fun upperBound(size: Int): Long {
            val sampled = (size.toLong() + sampleSize - 1) / sampleSize
            return if (scaled) (sampled * targetDensity + DENSITY - 1) / DENSITY else sampled
        }
    }

    private fun factoryScale(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): FactoryScale {
        var sampleSize = 1
        while (sourceWidth / (sampleSize * 2) >= width && sourceHeight / (sampleSize * 2) >= height) {
            sampleSize *= 2
        }
        val scale = maxOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight) * sampleSize
        return FactoryScale(sampleSize, scale < 1.0, (DENSITY * scale).roundToInt().coerceAtLeast(1))
    }

    /** Immutable pixel reservation, including codec sampling and native density rounding. */
    fun sharedByteBudget(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Long {
        val (reservedWidth, reservedHeight) = if (Build.VERSION.SDK_INT >= 28) width.toLong() to height.toLong()
        else factoryScale(sourceWidth, sourceHeight, width, height).let {
            it.upperBound(sourceWidth) to it.upperBound(sourceHeight)
        }
        // Saturate invalidly large headers so callers reject them without overflowing the budget.
        return if (reservedWidth > Long.MAX_VALUE / 4 / reservedHeight) Long.MAX_VALUE
            else reservedWidth * reservedHeight * 4
    }

    fun decode(file: File?, bytes: ByteArray?, sourceWidth: Int, sourceHeight: Int,
               width: Int, height: Int, options: SvgaDecodeOptions,
               inBitmap: Bitmap? = null, mutable: Boolean = false): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= 28 && !mutable && inBitmap == null) {
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
            val scale = factoryScale(sourceWidth, sourceHeight, width, height)
            val opts = BitmapFactory.Options().apply {
                inMutable = mutable || inBitmap != null
                this.inBitmap = inBitmap
                inPreferredConfig = options.bitmapConfig
                if (mutable && Build.VERSION.SDK_INT >= 26) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
                // Native density scaling avoids an application-side full-size bitmap + resized copy.
                inSampleSize = scale.sampleSize
                inScaled = scale.scaled
                inDensity = DENSITY
                inTargetDensity = scale.targetDensity
            }
            fun decode() = if (file != null) BitmapFactory.decodeFile(file.path, opts)
                else requireNotNull(bytes).let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
            try { decode() } catch (e: IllegalArgumentException) {
                if (inBitmap == null) throw e
                // Native decoders may reject an otherwise sufficiently sized allocation.
                opts.inBitmap = null
                inBitmap.recycle()
                decode()
            }
        }
        // Rendering uses an explicit matrix; synthetic density must never scale pixels again.
        bitmap?.density = Bitmap.DENSITY_NONE
        return bitmap
    }
}
