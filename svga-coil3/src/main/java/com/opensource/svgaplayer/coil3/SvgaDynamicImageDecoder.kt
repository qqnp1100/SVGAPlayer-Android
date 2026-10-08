package com.opensource.svgaplayer.coil3

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.FilterInputStream
import java.io.IOException

/** Inspect encoded dimensions before delegating decoding, so FILL cannot allocate an unbounded axis. */
internal class SvgaDynamicImageDecoder(private val target: SvgaDynamicImageSize, private val limit: Int) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder = object : Decoder {
        override suspend fun decode(): DecodeResult? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runInterruptible {
                result.source.source().peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val rotation = runInterruptible {
                try {
                    // ExifInterface must not mistake a streaming source's available() for its length.
                    object : FilterInputStream(result.source.source().peek().inputStream()) {
                        private var finished = false
                        override fun available() = if (finished) 0 else Int.MAX_VALUE
                        override fun read() = super.read().also { if (it < 0) finished = true }
                        override fun read(b: ByteArray, off: Int, len: Int) =
                            super.read(b, off, len).also { if (it < 0) finished = true }
                    }.use { ExifInterface(it).rotationDegrees }
                } catch (_: IOException) { 0 }
            }
            val swapped = rotation == 90 || rotation == 270
            val edge = target.decodeEdge(if (swapped) bounds.outHeight else bounds.outWidth,
                if (swapped) bounds.outWidth else bounds.outHeight, limit)
            val boundedOptions = options.copy(size = Size(edge, edge), scale = Scale.FIT)
            var index = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = imageLoader.components.newDecoder(result, boundedOptions, imageLoader, index) ?: return null
                index = next.second + 1
                next.first.decode()?.let { return it }
            }
        }
    }
}
