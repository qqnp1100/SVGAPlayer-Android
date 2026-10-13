package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.proto.MovieEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.InflaterInputStream

/** Run once before a change with frameStorageMode=baseline, then with frameStorageMode=verify. */
class SvgaFrameStorageComparisonTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun identities() = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun collect() {
        Runtime.getRuntime().gc()
        System.runFinalization()
        SystemClock.sleep(100)
        Runtime.getRuntime().gc()
    }

    @Test fun jsonKeepUsesThePrecedingShapesOfEachLayer() {
        fun shape(width: Int) = JSONObject().put("alpha", 1).put("shapes", JSONArray().put(
            JSONObject().put("type", "rect").put("args", JSONObject().put("width", width).put("height", 10))))
        // Non-object shape entries were skipped by the legacy JSON reader.
        val keep = JSONObject().put("alpha", 1).put("shapes", JSONArray().put(JSONObject.NULL)
            .put(JSONObject().put("type", "keep")))
        val json = JSONObject().put("movie", JSONObject().put("fps", 20).put("frames", 4)
            .put("viewBox", JSONObject().put("width", 100).put("height", 100)))
            .put("sprites", JSONArray()
                .put(JSONObject().put("imageKey", "a").put("frames", JSONArray().put(shape(10)).put(keep).put(JSONObject()).put(keep)))
                .put(JSONObject().put("imageKey", "b").put("frames", JSONArray().put(shape(20)).put(keep))))
        val video = SVGAVideoEntity(json, context.cacheDir)
        try {
            val sprites = field(video, "spriteList") as List<*>
            val first = field(sprites[0]!!, "frames") as List<*>
            val second = field(sprites[1]!!, "frames") as List<*>
            assertEquals(4, first.size); assertEquals(2, second.size)
            assertSame(field(first[0]!!, "shapes"), field(first[1]!!, "shapes"))
            assertSame(field(second[0]!!, "shapes"), field(second[1]!!, "shapes"))
            assertNotSame(first[1], second[1])
            assertTrue((field(first[3]!!, "shapes") as List<*>).isEmpty())
        } finally { video.clear() }
    }

    @Test fun jsonFrameEqualityPreservesArrayOrderAndIgnoresObjectKeyOrder() {
        val first = JSONObject("""{"alpha":1,"layout":{"x":0,"y":0,"width":40,"height":20},"shapes":[{"type":"rect","args":{"width":40,"height":20},"styles":{"lineDash":[1,2,3]}}]}""")
            .put("extra", JSONArray().put(null))
        val second = JSONObject().put("extra", JSONArray().put(JSONObject.NULL))
            .put("shapes", first.getJSONArray("shapes"))
            .put("layout", JSONObject("""{"height":20,"width":40,"y":0,"x":0}"""))
            .put("alpha", 1)
        val different = JSONObject(second.toString()).apply {
            getJSONArray("shapes").getJSONObject(0).getJSONObject("styles")
                .put("lineDash", JSONArray().put(3).put(2).put(1))
        }
        val json = JSONObject().put("movie", JSONObject().put("fps", 20).put("frames", 3)
            .put("viewBox", JSONObject().put("width", 100).put("height", 100)))
            .put("sprites", JSONArray().put(JSONObject().put("imageKey", "a")
                .put("frames", JSONArray().put(first).put(second).put(different))))
        val video = SVGAVideoEntity(json, context.cacheDir)
        try {
            val sprite = (field(video, "spriteList") as List<*>).single()!!
            val frames = field(sprite, "frames") as List<*>
            assertSame(frames[0], frames[1])
            assertNotSame(frames[1], frames[2])
            assertEquals(3, frames.size)
        } finally { video.clear() }
    }

    private fun storage(entity: SVGAVideoEntity): JSONObject {
        val frames = identities(); val transforms = identities(); val layouts = identities()
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : java.io.OutputStream() { override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
        val out = DataOutputStream(DigestOutputStream(sink, digest))
        val values = FloatArray(9)
        var records = 0
        val sprites = field(entity, "spriteList") as List<*>
        out.writeInt(sprites.size)
        for (sprite in sprites) {
            out.writeUTF(field(sprite!!, "imageKey") as? String ?: "")
            out.writeUTF(field(sprite, "matteKey") as? String ?: "")
            val indexed = field(sprite, "frames") as List<*>
            out.writeInt(indexed.size)
            for (frame in indexed) {
                frame!!; records++; frames.add(frame)
                out.writeDouble(field(frame, "alpha") as Double)
                val layout = field(frame, "layout")!!; layouts.add(layout)
                for (name in listOf("x", "y", "width", "height"))
                    out.writeDouble(layout.javaClass.getMethod("get${name.replaceFirstChar { it.uppercaseChar() }}").invoke(layout) as Double)
                val transform = field(frame, "transform")!!; transforms.add(transform)
                transform.javaClass.getMethod("getValues", FloatArray::class.java).apply { isAccessible = true }.invoke(transform, values)
                values.forEach { out.writeFloat(it) }
                val mask = field(frame, "maskPath")
                val path = mask?.let { field(it, "replacedValue") as String } ?: ""
                val encodedPath = path.toByteArray(Charsets.UTF_8)
                out.writeInt(encodedPath.size); out.write(encodedPath)
            }
        }
        out.close()
        return JSONObject().put("frameRecords", records).put("uniqueFrames", frames.size)
            .put("uniqueLayouts", layouts.size).put("uniqueTransforms", transforms.size)
            .put("retainedFrameMatrices", transforms.count { it is android.graphics.Matrix })
            .put("indexedDataSha256", hex(digest.digest()))
    }

    private suspend fun decodeSample(name: String, directory: File): SvgaResource {
        if (name != "Castle.svga") return context.assets.open(name).use { SvgaResource.decode(it, directory, 128, 128) }
        // This historical fixture has ID3 bytes under img_122 without usable audio metadata.
        // Both APKs use the existing legacy reader, which already skips ID3 image entries.
        val movie = context.assets.open(name).use { InflaterInputStream(it).use { stream -> MovieEntity.ADAPTER.decode(stream) } }
        val video = SVGAVideoEntity(movie, directory, 128, 128)
        video.parserImages(android.view.View(context))
        val scratch = android.graphics.Path()
        for (sprite in field(video, "spriteList") as List<*>) {
            for (frame in field(sprite!!, "frames") as List<*>) {
                for (shape in field(frame!!, "shapes") as List<*>) {
                    shape!!.javaClass.getMethod("buildPath").apply { isAccessible = true }.invoke(shape)
                }
                (field(frame, "maskPath") as? com.opensource.svgaplayer.entities.SVGAPathEntity)?.buildPath(scratch)
            }
        }
        video.movieItem = null
        return SvgaResource::class.java.getDeclaredConstructor(SVGAVideoEntity::class.java)
            .apply { isAccessible = true }.newInstance(video)
    }

    private suspend fun sample(name: String, baseline: JSONObject?): JSONObject {
        collect()
        val runtime = Runtime.getRuntime()
        val javaBefore = runtime.totalMemory() - runtime.freeMemory()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val measuring = java.util.concurrent.atomic.AtomicBoolean(true)
        val peakJava = java.util.concurrent.atomic.AtomicLong(javaBefore)
        val peakNative = java.util.concurrent.atomic.AtomicLong(nativeBefore)
        val peakHeap = java.util.concurrent.atomic.AtomicLong(javaBefore + nativeBefore)
        val sampler = Thread({
            while (measuring.get()) {
                val java = runtime.totalMemory() - runtime.freeMemory()
                val native = Debug.getNativeHeapAllocatedSize()
                peakJava.set(maxOf(peakJava.get(), java)); peakNative.set(maxOf(peakNative.get(), native))
                peakHeap.set(maxOf(peakHeap.get(), java + native))
                SystemClock.sleep(5)
            }
        }, "svga-frame-memory-sampler").apply { start() }
        val directory = File(context.cacheDir, "frame-storage-comparison").apply { mkdirs() }
        val start = SystemClock.elapsedRealtimeNanos()
        val resource = try { decodeSample(name, directory) }
            finally { measuring.set(false); sampler.join(); directory.deleteRecursively() }
        val decodeMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        collect()
        val javaBytes = runtime.totalMemory() - runtime.freeMemory() - javaBefore
        val nativeBytes = Debug.getNativeHeapAllocatedSize() - nativeBefore
        val report = storage(resource.newVideoEntity()).put("asset", name).put("frames", resource.frames)
            .put("decodePath", if (name == "Castle.svga") "legacy (embedded ID3 audio fixture)" else "SvgaResource.decode")
            .put("fps", resource.fps).put("width", resource.width).put("height", resource.height)
            .put("resourceSizeEstimateBytes", resource.sizeBytes).put("decodeMillis", decodeMillis)
            .put("retainedJavaDeltaBytes", javaBytes).put("retainedNativeDeltaBytes", nativeBytes)
            .put("peakJavaDeltaBytes", peakJava.get() - javaBefore)
            .put("peakNativeDeltaBytes", peakNative.get() - nativeBefore)
            .put("peakHeapDeltaBytes", peakHeap.get() - javaBefore - nativeBefore)
        if (baseline != null) {
            for (key in listOf("frames", "fps", "width", "height", "frameRecords", "indexedDataSha256"))
                assertEquals("$name: $key", baseline.get(key), report.get(key))
            assertEquals("Per-frame Matrix retained in $name", 0, report.getInt("retainedFrameMatrices"))
            assertTrue(report.getInt("uniqueFrames") <= baseline.getInt("uniqueFrames"))
        }
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashes = JSONArray()
        val hitHashes = JSONArray()
        val canvas = Canvas(bitmap)
        val renderStart = SystemClock.elapsedRealtimeNanos()
        try {
            resource.newRenderer().use { renderer ->
                fun draw(frame: Int): String {
                    bitmap.eraseColor(0)
                    renderer.draw(canvas, 128, 128, frame, frame * 1_000_000_000L / resource.fps)
                    buffer.clear(); bitmap.copyPixelsToBuffer(buffer)
                    return hex(digest.digest(buffer.array()))
                }
                for (frame in 0 until resource.frames) {
                    val hash = draw(frame); hashes.put(hash)
                    val hits = listOf(16f, 64f, 112f).flatMap { x -> listOf(16f, 64f, 112f).map { y -> renderer.hitTest(x, y) } }
                    val hitHash = hex(digest.digest(JSONArray(hits).toString().toByteArray(Charsets.UTF_8)))
                    hitHashes.put(hitHash)
                    if (baseline != null) assertEquals("$name pixels at frame $frame",
                        baseline.getJSONArray("pixelSha256").getString(frame), hash)
                    if (baseline != null) assertEquals("$name hit test at frame $frame",
                        baseline.getJSONArray("hitSha256").getString(frame), hitHash)
                }
                // Exercise reverse and non-sequential access with the same immutable frame storage.
                for (frame in listOf(resource.frames - 1, 0, resource.frames / 2, 1, resource.frames - 2).filter { it >= 0 })
                    assertEquals("$name seek/reverse at $frame", hashes.getString(frame), draw(frame))
                if (baseline != null) {
                    // Closing one presentation must not clear data shared with another presentation.
                    resource.newRenderer().close()
                    assertEquals(hashes.getString(resource.frames / 2), draw(resource.frames / 2))
                }
            }
        } finally { bitmap.recycle() }
        return report.put("pixelSha256", hashes).put("hitSha256", hitHashes)
            .put("renderAndHashMillis", (SystemClock.elapsedRealtimeNanos() - renderStart) / 1_000_000.0)
    }

    @Test fun existingAssetsPreserveEveryFrameAndReportMemory() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("frameStorageMode")
        assumeTrue("Optional before/after comparison", mode == "baseline" || mode == "verify")
        val baselineFile = File(context.getExternalFilesDir(null), "frame-storage-baseline.json")
        val baseline = if (mode == "verify") JSONObject(baselineFile.readText()) else null
        val samples = JSONObject()
        // Initialize framework/native decoding before measuring retained resource deltas.
        val warmup = File(context.cacheDir, "frame-storage-warmup").apply { mkdirs() }
        try { context.assets.open("matteRect.svga").use { SvgaResource.decode(it, warmup, 128, 128).newRenderer().close() } }
        finally { warmup.deleteRecursively() }
        val assets = context.assets.list("").orEmpty().filter { it.endsWith(".svga") }.sorted()
        for (name in assets) {
            val report = sample(name, baseline?.getJSONObject("samples")?.getJSONObject(name))
            samples.put(name, report)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "\n$name: records=${report.getInt("frameRecords")}, unique=${report.getInt("uniqueFrames")}, matrices=${report.getInt("retainedFrameMatrices")}\n")
            })
        }
        val report = JSONObject().put("mode", mode).put("device", android.os.Build.MODEL)
            .put("api", android.os.Build.VERSION.SDK_INT).put("viewport", "128x128").put("samples", samples)
        File(context.getExternalFilesDir(null), "frame-storage-$mode.json").writeText(report.toString(2))
        assertTrue(assets.isNotEmpty())
        if (baseline != null) assertEquals(baseline.getJSONObject("samples").length(), assets.size)
    }
}
