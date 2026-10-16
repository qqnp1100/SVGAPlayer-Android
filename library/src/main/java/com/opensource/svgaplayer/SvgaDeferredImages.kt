package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import com.opensource.svgaplayer.bitmap.SvgaImageDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
    private var displayedFrame = -1
    private val drawingFrames = HashMap<Int, Int>()
    private var window = emptyList<Int>()
    private var windowStart = 0
    private var windowEnd = 0
    private var direction = 1
    private var generation = 0L
    private var prefetchEnabled = false
    private var prefetchStarted = false
    private var prefetchScope: CoroutineScope? = null
    private val prefetchRequests = Channel<Unit>(Channel.CONFLATED)
    private val decodeErrors = HashMap<String, Exception>()
    private var retainAll = false
    private var closed = false

    companion object {
        private const val LOOK_AHEAD = 3
        private const val MAX_BUFFER_BYTES = 16L * 1024 * 1024
        // Bound speculative CPU work across Views, without serializing their required decodes.
        private val prefetchDispatcher = Dispatchers.Default.limitedParallelism(2)
    }

    fun configurePrefetch(startFrame: Int, endFrame: Int, reverse: Boolean): Unit = synchronized(lock) {
        if (closed) return
        val nextDirection = if (reverse) -1 else 1
        if (prefetchEnabled && windowStart == startFrame && windowEnd == endFrame && direction == nextDirection) return
        prefetchEnabled = true
        windowStart = startFrame; windowEnd = endFrame; direction = nextDirection
        window = emptyList(); generation++
    }

    /** Called before dispatch, so a cancelled worker cannot move a newer seek's window back. */
    fun request(frame: Int): Long = synchronized(lock) {
        if (!closed && (requestedFrame != frame || (prefetchEnabled && window.firstOrNull() != frame))) {
            requestedFrame = frame
            window = if (prefetchEnabled) (0..LOOK_AHEAD).map { frame + it * direction }
                .filter { it in windowStart..windowEnd } else emptyList()
            generation++
            // A seek can discard unshown speculative pixels immediately. Displayed pixels
            // still retire through afterDraw and its hardware fence.
            if (prefetchEnabled && !retainAll) {
                val obsolete = ArrayList<Pixels>()
                val iterator = bitmaps.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val usedFrame = sources.getValue(entry.key).frame
                    if (!entry.value.usedByHardware && usedFrame != displayedFrame && usedFrame !in drawingFrames &&
                        usedFrame != frame && usedFrame !in window) {
                        obsolete.add(entry.value); iterator.remove()
                    }
                }
                release(obsolete)
            }
        }
        generation
    }

    fun requestPrefetch() = synchronized(lock) {
        if (!closed && prefetchStarted && !retainAll && window.drop(1).any { frame ->
                byFrame[frame].orEmpty().any { it !in bitmaps && it !in decodeErrors }
            }) prefetchRequests.trySend(Unit)
        Unit
    }

    /** Warm a bounded window before the clock starts; later refills run independently of it. */
    fun preparePlayback(frame: Int) {
        val ticket = synchronized(lock) {
            if (closed) return
            if (prefetchEnabled && requestedFrame >= 0 && requestedFrame != frame) null else request(frame)
        }
        if (ticket != null) { prepare(frame, ticket); prefetch(ticket) }
        synchronized(lock) {
            if (closed || !prefetchEnabled || prefetchStarted) return
            prefetchStarted = true
            prefetchScope = CoroutineScope(SupervisorJob() + prefetchDispatcher).also { scope ->
                scope.launch {
                    for (ignored in prefetchRequests) {
                        ensureActive()
                        prefetch(synchronized(lock) { generation }, coroutineContext)
                    }
                }
            }
            // A seek may have superseded warmup before the worker existed.
            requestPrefetch()
        }
    }

    private fun prefetch(ticket: Long, context: CoroutineContext = EmptyCoroutineContext): Unit = synchronized(decodeLock) {
        context.ensureActive()
        val frames = synchronized(lock) {
            if (closed || !prefetchEnabled || retainAll || generation != ticket) return
            if (byFrame[window.firstOrNull()].orEmpty().any { it !in bitmaps }) return
            window.drop(1)
        }
        for (frame in frames) {
            val keys = synchronized(lock) { byFrame[frame].orEmpty().toList() }
            for (key in keys) {
                try { if (!decode(key, ticket, speculative = true, context = context)) return }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Report malformed images when requested, not as an uncaught worker exception.
                    synchronized(lock) { if (!closed) decodeErrors[key] = e }
                    return
                }
            }
        }
    }

    fun bitmap(key: String): Bitmap? = synchronized(lock) { bitmaps[key]?.bitmap }
    fun beginDraw(frame: Int) = synchronized(lock) { drawingFrames[frame] = (drawingFrames[frame] ?: 0) + 1 }
    fun endDraw(frame: Int) = synchronized(lock) {
        val count = (drawingFrames[frame] ?: 1) - 1
        if (count == 0) drawingFrames.remove(frame) else drawingFrames[frame] = count
        Unit
    }
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
        // A redraw of the old visible frame must not supersede a pending seek's window.
        if (isReady(frame)) return
        prepare(frame, request(frame))
    }

    fun prepare(frame: Int, ticket: Long, context: CoroutineContext = EmptyCoroutineContext) {
        context.ensureActive()
        // Drawing an already ready frame must never wait behind speculative decoding.
        if (isReady(frame)) return
        synchronized(decodeLock) {
            // Monitor waits are not cancellable: recheck before doing any native work.
            context.ensureActive()
            val activeTicket: Long
            val keys = synchronized(lock) {
                if (closed || (generation != ticket && requestedFrame != frame)) return
                activeTicket = generation
                byFrame[frame].orEmpty().toList()
            }
            keys.forEach { if (!decode(it, activeTicket, context = context)) return }
        }
    }

    /** Existing View controls can change a single-play presentation into a looping one. */
    fun prepareAll() = synchronized(decodeLock) {
        val keys = synchronized(lock) { if (closed) return; retainAll = true; sources.keys.toList() }
        keys.forEach { decode(it) }
    }

    private fun decode(key: String, ticket: Long? = null, speculative: Boolean = false,
                       context: CoroutineContext = EmptyCoroutineContext): Boolean {
        context.ensureActive()
        val image: SvgaDeferredImage
        val reusable: Boolean
        val candidate: Bitmap?
        val reservation: Long
        synchronized(lock) {
            if (closed || (ticket != null && ticket != generation)) return false
            if (bitmaps.containsKey(key)) return true
            decodeErrors[key]?.let { throw it }
            image = sources[key] ?: return false
            reusable = key in reusableKeys
            val required = image.width.toLong() * image.height * 4
            candidate = if (reusable) pool.filter { !it.isRecycled && it.isMutable && it.allocationByteCount >= required }
                .minByOrNull { it.allocationByteCount } else null
            if (candidate != null) {
                pool.remove(candidate); poolBytes -= candidate.allocationByteCount
                allocatedBytes -= candidate.allocationByteCount
            }
            reservation = maxOf(required, candidate?.allocationByteCount?.toLong() ?: 0)
            val limit = if (speculative) minOf(maxBytes, MAX_BUFFER_BYTES) else maxBytes
            while (allocatedBytes > limit - reservation && pool.isNotEmpty()) {
                val removed = pool.removeAt(pool.lastIndex)
                poolBytes -= removed.allocationByteCount; allocatedBytes -= removed.allocationByteCount
                removed.recycle()
            }
            if (reservation > limit - allocatedBytes) {
                candidate?.recycle()
                if (speculative) return false
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
            context.ensureActive()
            result.prepareToDraw()
            synchronized(lock) {
                if (closed) { result.recycle(); return false }
                // BitmapFactory cannot be interrupted. Discard its result if its request was cancelled.
                context.ensureActive()
                val actual = result.allocationByteCount.toLong()
                require(actual <= maxBytes - (allocatedBytes - reservation)) { "Decoded image budget exceeded" }
                allocatedBytes += actual - reservation
                if (ticket != null && ticket != generation && !retainAll &&
                    image.frame != requestedFrame && image.frame !in window) {
                    release(listOf(Pixels(result, reusable)))
                    return false
                }
                bitmaps[key] = Pixels(result, reusable)
            }
            return true
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
            displayedFrame = frame
            if (retainAll) return
            val removed = ArrayList<Pixels>()
            val iterator = bitmaps.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val usedFrame = sources.getValue(entry.key).frame
                if (usedFrame != frame && usedFrame != requestedFrame && usedFrame !in window) {
                    removed.add(entry.value); iterator.remove()
                }
            }
            if (!prefetchEnabled && requestedFrame == frame) requestedFrame = -1
            removed
        }
        val software = retired.filterNot { it.usedByHardware }
        release(software)
        requestPrefetch()
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
        requestPrefetch()
    }

    fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        generation++
        prefetchRequests.close(); prefetchScope?.cancel(); prefetchScope = null
        pool.forEach { it.recycle() }; pool.clear()
        // Only hardware commands and in-progress draws can still reference presentation pixels.
        // Unshown lookahead allocations can be reclaimed immediately on cancel/rebind.
        bitmaps.forEach { (key, pixels) ->
            if (!pixels.usedByHardware && sources.getValue(key).frame !in drawingFrames) pixels.bitmap.recycle()
        }
        bitmaps.clear(); sources = emptyMap(); byFrame = emptyMap()
        reusableKeys.clear()
        window = emptyList(); decodeErrors.clear(); drawingFrames.clear()
        allocatedBytes = 0; poolBytes = 0
    }
}
