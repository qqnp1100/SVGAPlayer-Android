package com.opensource.svgaplayer

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.widget.ImageView
import com.opensource.svgaplayer.bitmap.SvgaImageDecoder
import com.opensource.svgaplayer.bitmap.SvgaImageSize
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import com.opensource.svgaplayer.proto.MovieEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/** Shared immutable data. Deferred images keep encoded bytes; their pixels belong to presentations. */
class SvgaResource private constructor(private val data: SVGAVideoEntity) {
    val width get() = data.videoSize.width.toInt()
    val height get() = data.videoSize.height.toInt()
    val frames get() = data.frames
    val fps get() = data.FPS
    val layerKeys: List<String> get() = data.spriteList.mapNotNull { it.imageKey }.filterNot { it.endsWith(".matte") }.distinct()
    val sizeBytes: Long get() = audioTracks.sumOf { it.bytes.size.toLong() } + data.imageMap.values.sumOf { it.allocationByteCount.toLong() } +
        data.deferredImageSources.values.sumOf { it.bytes.size.toLong() } +
        data.spriteList.sumOf { it.frames.size.toLong() * 256 } + 1024
    fun newVideoEntity(): SVGAVideoEntity = SVGAVideoEntity(data).also { it.resource = this }
    fun layerSize(key: String): android.util.Size? {
        val sprite = data.spriteList.firstOrNull { it.imageKey?.removeSuffix(".matte") == key } ?: return null
        return android.util.Size(sprite.frames.maxOfOrNull { it.layout.width.toInt() } ?: 0,
            sprite.frames.maxOfOrNull { it.layout.height.toInt() } ?: 0)
    }
    /** Maximum image fill size across all uses, including frame transforms and presentation scale. */
    fun layerImageSize(key: String, scaleX: Float = 1f, scaleY: Float = 1f): android.util.Size? {
        require(scaleX.isFinite() && scaleX > 0 && scaleY.isFinite() && scaleY > 0)
        val usage = SvgaImageSize()
        val matrix = FloatArray(9)
        data.spriteList.filter { it.imageKey?.removeSuffix(".matte") == key.removeSuffix(".matte") }.forEach { sprite ->
            val matte = sprite.imageKey?.endsWith(".matte") == true
            sprite.frames.forEach { frame ->
                frame.transform.getValues(matrix)
                usage.include(frame.layout.width, frame.layout.height,
                    matrix[Matrix.MSCALE_X].toDouble() * scaleX, matrix[Matrix.MSKEW_Y].toDouble() * scaleY,
                    matrix[Matrix.MSKEW_X].toDouble() * scaleX, matrix[Matrix.MSCALE_Y].toDouble() * scaleY,
                    visible = matte || !(frame.alpha <= 0.0))
            }
        }
        val size = usage.displayedSize() ?: return null
        return android.util.Size(size.first, size.second)
    }
    fun newRenderer(bindings: SVGADynamicEntity = SVGADynamicEntity()) = SvgaRenderer(newVideoEntity(), bindings)

    companion object {
        const val MAX_INPUT_BYTES = 64L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 128L * 1024 * 1024

        /** Caller supplies a private staging directory, which may be removed after this returns. */
        suspend fun decode(input: InputStream, directory: File, width: Int = 0, height: Int = 0,
            maxDecodedBytes: Long = MAX_EXPANDED_BYTES,
            decodeOptions: SvgaDecodeOptions = SvgaDecodeOptions.defaults): SvgaResource {
            val job = currentCoroutineContext()
            fun bounded(stream: InputStream, limit: Long) = object : FilterInputStream(stream) {
                var total = 0L
                private fun count(n: Int): Int { job.ensureActive(); if (n > 0) {
                    total += n; require(total <= limit) { "SVGA stream exceeds $limit bytes" }
                }; return n }
                override fun read(): Int { val n = super.read(); count(if (n < 0) 0 else 1); return n }
                override fun read(b: ByteArray, off: Int, len: Int) = count(`in`.read(b, off, len))
            }
            val stream = bounded(input, MAX_INPUT_BYTES).buffered()
            stream.mark(4)
            val zip = stream.read() == 0x50 && stream.read() == 0x4b
            stream.reset()
            val video: SVGAVideoEntity
            if (zip) {
                directory.mkdirs()
                var total = 0L
                var entries = 0
                ZipInputStream(stream).use { archive ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        job.ensureActive()
                        val entry = archive.nextEntry ?: break
                        require(++entries <= 4096) { "Too many ZIP entries" }
                        val file = File(directory, entry.name).canonicalFile
                        require(file.path.startsWith(directory.canonicalPath + File.separator)) { "Unsafe ZIP entry" }
                        if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { out ->
                                while (true) {
                                    job.ensureActive()
                                    val n = archive.read(buffer); if (n < 0) break
                                    total += n; require(total <= MAX_EXPANDED_BYTES) { "ZIP too large" }
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        archive.closeEntry()
                    }
                }
                val binary = File(directory, "movie.binary")
                video = if (binary.isFile) binary.inputStream().use { SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), directory) }
                else SVGAVideoEntity(JSONObject(File(directory, "movie.spec").readText()), directory)
            } else {
                video = bounded(InflaterInputStream(stream), MAX_EXPANDED_BYTES).use {
                    SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), directory)
                }
            }
            require(video.frames in 1..100000 && video.FPS in 1..240 &&
                video.videoSize.width > 0 && video.videoSize.height > 0) { "Invalid SVGA metadata" }
            val json = if (zip && video.movieItem == null) JSONObject(File(directory, "movie.spec").readText()).optJSONObject("images") else null
            val images = video.movieItem?.images.orEmpty()
            val audioKeys = video.movieItem?.audios.orEmpty().mapNotNull { it.audioKey }.toSet()
            val keys = if (json != null) json.keys().asSequence().toList() else images.keys.toList()
            val usage = video.imageUsage()
            job.ensureActive()
            val viewportScale = if (width > 0 && height > 0)
                maxOf(width / video.videoSize.width, height / video.videoSize.height) else Double.NaN
            var pixels = 0L
            for (key in keys) {
                job.ensureActive()
                if (key in audioKeys) continue
                val imageUsage = usage[key.removeSuffix(".matte")]
                if ((decodeOptions.skipInvisibleImages || decodeOptions.inBitmap) && imageUsage?.hasVisibleFrame != true) continue
                val bytes = images[key]?.toByteArray()
                fun imageFile(name: String): File {
                    val file = listOf(name, "$name.png", "$key.png").map { File(directory, it).canonicalFile }
                        .firstOrNull { it.path.startsWith(directory.canonicalPath + File.separator) && it.isFile }
                    return requireNotNull(file) { "Missing image $key" }
                }
                var file = if (json != null) imageFile(json.getString(key)) else null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (file != null) BitmapFactory.decodeFile(file.path, opts)
                else BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size, opts)
                if (opts.outWidth <= 0 && bytes != null && bytes.size < 4096) {
                    file = imageFile(bytes.toString(Charsets.UTF_8)); BitmapFactory.decodeFile(file.path, opts)
                }
                require(opts.outWidth > 0 && opts.outHeight > 0) { "Invalid image $key" }
                val target = SvgaImageSize.target(imageUsage, opts.outWidth, opts.outHeight, viewportScale)
                if (decodeOptions.inBitmap && imageUsage?.visibleUseCount == 1) {
                    video.deferredImageSources[key.removeSuffix(".matte")] = SvgaDeferredImage(
                        file?.readBytes() ?: requireNotNull(bytes), checkNotNull(imageUsage.singleUseFrame),
                        opts.outWidth, opts.outHeight, target.first, target.second, decodeOptions)
                    continue
                }
                // RGB_565 is a preference: alpha images can still require four bytes per pixel.
                require(target.first.toLong() <= (maxDecodedBytes - pixels) / 4 / target.second) {
                    "Decoded image budget exceeded"
                }
                val bitmap = requireNotNull(SvgaImageDecoder.decode(file, bytes, opts.outWidth, opts.outHeight,
                    target.first, target.second, decodeOptions)) { "Cannot decode $key" }
                if (bitmap.allocationByteCount.toLong() > maxDecodedBytes - pixels) {
                    bitmap.recycle()
                    error("Decoded image budget exceeded")
                }
                pixels += bitmap.allocationByteCount
                video.imageMap[key.removeSuffix(".matte")] = bitmap.apply { prepareToDraw() }
            }
            video.deferredImageBudget = maxDecodedBytes - pixels
            video.deferredImageSources.values.groupBy { it.frame }.values.forEach { imagesInFrame ->
                require(imagesInFrame.sumOf { it.width.toLong() * it.height * 4 } <= video.deferredImageBudget) {
                    "Decoded image budget exceeded"
                }
            }
            val scratch = Path()
            video.spriteList.forEach { sprite -> sprite.frames.forEach { frame ->
                job.ensureActive()
                frame.shapes.forEach { it.buildPath() }
                frame.maskPath?.buildPath(scratch)
            } }
            // Only deferred images retain encoded bytes, independent of temporary staging files.
            val resource = SvgaResource(video)
            resource.audioTracks = video.movieItem?.audios.orEmpty().mapNotNull { audio ->
                images[audio.audioKey]?.let { SvgaAudioTrack(it.toByteArray(), audio.startFrame ?: 0,
                    audio.endFrame ?: video.frames, audio.startTime ?: 0) }
            }
            video.movieItem = null
            return resource
        }
    }
    internal var audioTracks: List<SvgaAudioTrack> = emptyList()
}

internal data class SvgaAudioTrack(val bytes: ByteArray, val startFrame: Int, val endFrame: Int, val startTimeMillis: Int)

/** Platform-neutral viewport contract; no View is created by this renderer. */
class SvgaRenderer internal constructor(private val video: SVGAVideoEntity, val bindings: SVGADynamicEntity) : AutoCloseable {
    private val drawer = SVGACanvasDrawer(video, bindings)
    private var drawnFrame = -1
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val inverse = android.graphics.Matrix()
    private val point = FloatArray(2)
    val hasActiveScrollingText get() = drawer.hasActiveScrollingText
    fun prepare(width: Int, height: Int) {
        video.deferredImages?.prepare(0)
        val picture = android.graphics.Picture()
        val canvas = picture.beginRecording(width, height)
        try { drawer.renderFrame(canvas, 0, ImageView.ScaleType.FIT_XY, width, height, 0) }
        finally { picture.endRecording() }
    }
    fun draw(canvas: Canvas, width: Int, height: Int, frame: Int, timeNanos: Long) {
        if (width <= 0 || height <= 0) return
        video.deferredImages?.prepare(frame)
        viewportWidth = width; viewportHeight = height; drawnFrame = frame
        val save = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            drawer.renderFrame(canvas, frame, ImageView.ScaleType.FIT_XY, width, height, timeNanos)
        } finally { canvas.restoreToCount(save) }
        video.deferredImages?.afterDraw(frame, canvas)
    }
    /** Hit test the last displayed frame, inverse-transforming rotated/mirrored layers. */
    fun hitTest(x: Float, y: Float): String? {
        if (drawnFrame < 0) return null
        for (index in video.spriteList.indices.reversed()) {
            val sprite = video.spriteList[index]
            val key = sprite.imageKey ?: continue
            if (key.endsWith(".matte") || bindings.dynamicHidden[key] == true) continue
            val frame = sprite.frames.getOrNull(drawnFrame) ?: continue
            if (frame.alpha <= 0 || !frame.transform.invert(inverse)) continue
            point[0] = x * video.videoSize.width.toFloat() / viewportWidth
            point[1] = y * video.videoSize.height.toFloat() / viewportHeight
            inverse.mapPoints(point)
            if (point[0] >= 0 && point[1] >= 0 && point[0] < frame.layout.width && point[1] < frame.layout.height) return key
        }
        return null
    }
    fun pause() = drawer.pauseTextScrolling()
    override fun close() { drawer.clearCaches(); video.clear(); bindings.clearDynamicObjects() }
}
