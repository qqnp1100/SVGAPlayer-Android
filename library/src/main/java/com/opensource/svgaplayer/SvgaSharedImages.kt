package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.util.Size
import com.opensource.svgaplayer.bitmap.SvgaImageDecoder
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Resource-owned pixels: decode once per key, share across presentations, and never pool/recycle. */
internal class SvgaSharedImages(
    sources: Map<String, SvgaDeferredImage>,
    val frameKeys: Map<Int, List<String>>,
    private val onDecodeError: () -> Unit,
) {
    private class Entry(val source: SvgaDeferredImage) {
        val byteBudget = SvgaImageDecoder.sharedByteBudget(source.sourceWidth, source.sourceHeight, source.width, source.height)
        @Volatile var bitmap: Bitmap? = null
    }
    private val entries = sources.mapValues { Entry(it.value) }
    private val failed = AtomicBoolean()
    val hasDecodeError get() = failed.get()

    // Reserve the full pixel cost before the resource enters either memory cache. Its weight must
    // remain stable as background decoders publish pixels, including after an LRU/weak-cache hit.
    val reservedBytes = entries.values.sumOf { it.byteBudget }
    val sizeBytes = reservedBytes + sources.values.sumOf { it.bytes.size.toLong() + 128 } +
        frameKeys.values.sumOf { 32L + it.size * 8L }
    val allocationBytes: Long get() = entries.values.sumOf { it.bitmap?.allocationByteCount?.toLong() ?: 0L }

    fun contains(key: String) = key in entries
    fun bitmap(key: String): Bitmap? = entries[key]?.bitmap
    fun size(key: String): Size? = entries[key]?.source?.let { Size(it.width, it.height) }
    fun decodedBytes(key: String): Long = entries[key]?.byteBudget ?: 0

    fun reportDecodeError(error: Throwable) {
        if (error is Exception && error !is CancellationException && failed.compareAndSet(false, true)) {
            runCatching { onDecodeError() }
        }
    }

    fun decode(key: String, context: CoroutineContext) {
        context.ensureActive()
        val entry = entries[key] ?: return
        if (entry.bitmap != null) return
        // Independent keys do not block one another. Concurrent Views requesting the same key
        // share the native decode; cancelling one View cannot recycle another View's result.
        synchronized(entry) {
            context.ensureActive()
            if (entry.bitmap != null) return
            val image = entry.source
            var bitmap: Bitmap? = null
            try {
                bitmap = requireNotNull(SvgaImageDecoder.decode(null, image.bytes, image.sourceWidth,
                    image.sourceHeight, image.width, image.height, image.options)) { "Cannot decode $key" }
                context.ensureActive()
                require(bitmap.allocationByteCount.toLong() <= decodedBytes(key)) { "Decoded image budget exceeded" }
                bitmap.prepareToDraw()
                entry.bitmap = bitmap
            } catch (e: Throwable) {
                bitmap?.recycle()
                reportDecodeError(e)
                throw e
            }
        }
    }
}
