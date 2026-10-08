package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import com.opensource.svgaplayer.bitmap.SvgaImageDecoder

/** Encoded bytes outlive the loader's staging directory and can be shared by presentations. */
internal data class SvgaDeferredImage(
    val bytes: ByteArray,
    val frame: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val options: SvgaDecodeOptions,
)

/** A presentation owns mutable pixels. The shared resource never owns or caches these bitmaps. */
internal class SvgaDeferredImages(
    sources: Map<String, SvgaDeferredImage>,
    private val maxBytes: Long,
) {
    private data class Pixels(val bitmap: Bitmap, val reusable: Boolean, var usedByHardware: Boolean = false)
    private val lock = Any()
    private val decodeLock = Any()
    private var sources = sources
    private var byFrame = sources.entries.groupBy({ it.value.frame }, { it.key })
    // A lone resolution stays lazy, but has no reason to participate in bitmap reuse.
    private var reusableKeys = sources.entries.groupBy { it.value.width to it.value.height }
        .values.filter { it.size > 1 }.flatten().mapTo(HashSet()) { it.key }
    private val bitmaps = HashMap<String, Pixels>()
    private val pool = ArrayList<Bitmap>()
    private val maxPoolBytes = minOf(8L * 1024 * 1024, maxBytes)
    private var poolBytes = 0L
    @Volatile private var allocatedBytes = 0L
    private var requestedFrame = -1
    private var retainAll = false
    private var closed = false

    fun bitmap(key: String): Bitmap? = synchronized(lock) { bitmaps[key]?.bitmap }
    val allocationBytes: Long get() = synchronized(lock) { allocatedBytes }
    fun allReady(): Boolean = synchronized(lock) { closed || sources.keys.all { bitmaps.containsKey(it) } }
    fun size(key: String): android.util.Size? = synchronized(lock) {
        sources[key]?.let { android.util.Size(it.width, it.height) }
    }
    fun isReady(frame: Int): Boolean = synchronized(lock) {
        closed || byFrame[frame].orEmpty().all { bitmaps.containsKey(it) }
    }

    /** Call on a worker before publishing the new frame; the currently displayed pixels stay live. */
    fun prepare(frame: Int) {
        if (isReady(frame)) return
        synchronized(lock) { if (closed) return; requestedFrame = frame }
        synchronized(decodeLock) {
            val keys = synchronized(lock) { byFrame[frame].orEmpty().toList() }
            keys.forEach { decode(it) }
        }
    }

    /** Existing View controls can change a single-play presentation into a looping one. */
    fun prepareAll() = synchronized(decodeLock) {
        val keys = synchronized(lock) { retainAll = true; sources.keys.toList() }
        keys.forEach { decode(it) }
    }

    private fun decode(key: String) {
        val image: SvgaDeferredImage
        val reusable: Boolean
        val candidate: Bitmap?
        val reservation: Long
        synchronized(lock) {
            if (closed || bitmaps.containsKey(key)) return
            image = sources[key] ?: return
            reusable = key in reusableKeys
            val required = image.width.toLong() * image.height * 4
            candidate = if (reusable) pool.filter { !it.isRecycled && it.isMutable && it.allocationByteCount >= required }
                .minByOrNull { it.allocationByteCount } else null
            if (candidate != null) {
                pool.remove(candidate); poolBytes -= candidate.allocationByteCount
                allocatedBytes -= candidate.allocationByteCount
            }
            reservation = maxOf(required, candidate?.allocationByteCount?.toLong() ?: 0)
            while (allocatedBytes > maxBytes - reservation && pool.isNotEmpty()) {
                val removed = pool.removeAt(pool.lastIndex)
                poolBytes -= removed.allocationByteCount; allocatedBytes -= removed.allocationByteCount
                removed.recycle()
            }
            if (reservation > maxBytes - allocatedBytes) {
                candidate?.recycle()
                error("Decoded image budget exceeded")
            }
            allocatedBytes += reservation
        }
        var result: Bitmap? = null
        try {
            result = requireNotNull(SvgaImageDecoder.decode(null, image.bytes, image.sourceWidth,
                image.sourceHeight, image.width, image.height, image.options, candidate, mutable = reusable)) {
                "Cannot decode $key"
            }
            if (result !== candidate && candidate?.isRecycled == false) candidate.recycle()
            synchronized(lock) {
                if (closed) { result.recycle(); return }
                val actual = result.allocationByteCount.toLong()
                require(actual <= maxBytes - (allocatedBytes - reservation)) { "Decoded image budget exceeded" }
                allocatedBytes += actual - reservation
                bitmaps[key] = Pixels(result, reusable)
            }
        } catch (e: Throwable) {
            result?.takeUnless { it.isRecycled }?.recycle()
            candidate?.takeUnless { it.isRecycled }?.recycle()
            synchronized(lock) { if (!closed) allocatedBytes -= reservation }
            throw e
        }
    }

    /** Retire previous-frame pixels only after the replacement frame has actually been drawn. */
    fun afterDraw(frame: Int, canvas: Canvas, owner: View? = null) {
        val retired = synchronized(lock) {
            if (closed) return
            if (canvas.isHardwareAccelerated) {
                byFrame[frame].orEmpty().forEach { bitmaps[it]?.usedByHardware = true }
            }
            if (retainAll) return
            val removed = ArrayList<Pixels>()
            val iterator = bitmaps.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val usedFrame = sources.getValue(entry.key).frame
                if (usedFrame != frame && usedFrame != requestedFrame) {
                    removed.add(entry.value); iterator.remove()
                }
            }
            if (requestedFrame == frame) requestedFrame = -1
            removed
        }
        val software = retired.filterNot { it.usedByHardware }
        release(software)
        val hardware = retired.filter { it.usedByHardware }
        if (hardware.isEmpty()) return
        if (canvas.isHardwareAccelerated && owner != null && owner.isAttachedToWindow) {
            // A draw replacing the old commands must finish before decoding over their pixels.
            val release = Runnable { release(hardware) }
            if (Build.VERSION.SDK_INT >= 29 && owner.viewTreeObserver.isAlive) {
                owner.viewTreeObserver.registerFrameCommitCallback(release)
            } else {
                owner.post(release)
            }
        } else {
            // No owning View/fence is available. Leave native command references to GC.
            synchronized(lock) { if (!closed) allocatedBytes -= hardware.sumOf { it.bitmap.allocationByteCount.toLong() } }
        }
    }

    private fun release(pixels: List<Pixels>) = synchronized(lock) {
        pixels.forEach { pixels ->
            val bitmap = pixels.bitmap
            val bytes = bitmap.allocationByteCount.toLong()
            if (!closed && pixels.reusable && bitmap.isMutable && bytes <= maxPoolBytes - poolBytes) {
                pool.add(bitmap); poolBytes += bytes
            } else {
                if (!closed) allocatedBytes -= bytes
                bitmap.recycle()
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        pool.forEach { it.recycle() }; pool.clear()
        // Active pixels may still be referenced by a hardware display list; never recycle them.
        bitmaps.clear(); sources = emptyMap(); byFrame = emptyMap()
        reusableKeys.clear()
        allocatedBytes = 0; poolBytes = 0
    }
}
