package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SvgaDecodeOptions
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class SvgaBitmapDecodeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun frame(size: Int = 600, alpha: Double = 1.0, scale: Double = 1.0) = JSONObject()
        .put("alpha", alpha).put("layout", JSONObject().put("width", size).put("height", size))
        .put("transform", JSONObject().put("a", scale).put("d", scale))

    private fun sprite(key: String, vararg frames: JSONObject) = JSONObject()
        .put("imageKey", key).put("frames", JSONArray(frames.toList()))

    private fun fixture(alpha: Boolean = false): ByteArray {
        val pixels = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        pixels.setHasAlpha(alpha)
        pixels.eraseColor(if (alpha) 0x80ff0000.toInt() else Color.RED)
        val png = ByteArrayOutputStream()
        pixels.compress(Bitmap.CompressFormat.PNG, 100, png); pixels.recycle()
        val images = JSONObject()
        listOf("visible", "hidden", "unused", "mask").forEach { images.put(it, "$it.png") }
        val spec = JSONObject().put("movie", JSONObject().put("viewBox",
            JSONObject().put("width", 1000).put("height", 1000)).put("fps", 20).put("frames", 2))
            .put("images", images).put("sprites", JSONArray().apply {
                put(sprite("visible", frame(1200, scale = .5), frame(600)))
                // A later use of the same image must not overwrite the larger requirement.
                put(sprite("visible", frame(10), frame(10)))
                put(sprite("hidden", frame(alpha = 0.0), frame(alpha = 0.0)))
                put(sprite("mask.matte", frame(100, alpha = 0.0), frame(100, alpha = 0.0)))
            })
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec.toString().toByteArray()); zip.closeEntry()
                images.keys().forEach { key ->
                    zip.putNextEntry(ZipEntry("$key.png")); zip.write(png.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private suspend fun decode(bytes: ByteArray, options: SvgaDecodeOptions, budget: Long = 128L * 1024 * 1024): SvgaResource {
        val dir = File(context.cacheDir, "bitmap-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        return try { SvgaResource.decode(bytes.inputStream(), dir, 1000, 1000, budget, options) }
        finally { dir.deleteRecursively() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun images(resource: SvgaResource): Map<String, Bitmap> {
        val entity = resource.newVideoEntity()
        return try {
            (entity.javaClass.getDeclaredField("imageMap").apply { isAccessible = true }.get(entity) as Map<String, Bitmap>).toMap()
        } finally { entity.clear() }
    }

    @Test fun exactSamplingSkipsInvisibleImagesAndKeepsMatte() = runBlocking {
        val bytes = fixture()
        val all = decode(bytes, SvgaDecodeOptions())
        val filtered = decode(bytes, SvgaDecodeOptions(skipInvisibleImages = true))
        assertEquals(setOf("visible", "hidden", "unused", "mask"), images(all).keys)
        val bitmaps = images(filtered)
        assertEquals(setOf("visible", "mask"), bitmaps.keys)
        assertEquals(600, bitmaps.getValue("visible").width)
        assertEquals(600, bitmaps.getValue("visible").height)
        assertEquals(100, bitmaps.getValue("mask").width)
        assertTrue(filtered.sizeBytes < all.sizeBytes)
        // Filtering must leave every frame visually identical, including a transparent mask.
        val expected = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        try {
            all.newRenderer().use { a -> filtered.newRenderer().use { b ->
                for (frame in 0..1) {
                    expected.eraseColor(Color.TRANSPARENT); actual.eraseColor(Color.TRANSPARENT)
                    a.draw(Canvas(expected), 1000, 1000, frame, 0)
                    b.draw(Canvas(actual), 1000, 1000, frame, 0)
                    assertTrue(expected.sameAs(actual))
                }
            } }
        } finally { expected.recycle(); actual.recycle() }
    }

    @Test fun colorPreferencePreservesTransparencyAndChecksActualBudget() = runBlocking {
        val lowRam = SvgaDecodeOptions(Bitmap.Config.RGB_565, true)
        val opaque = images(decode(fixture(), lowRam)).getValue("visible")
        assertEquals(Bitmap.Config.RGB_565, opaque.config)
        val transparent = images(decode(fixture(true), lowRam)).getValue("visible")
        assertTrue(transparent.hasAlpha())
        assertEquals(128, Color.alpha(transparent.getPixel(20, 20)))
        assertTrue(runCatching { decode(fixture(true), lowRam, 1000) }.isFailure)
    }

    @Test fun globalDefaultsAndIndividualOverridesPartitionStrongAndWeakCaches() = runBlocking {
        val previous = SvgaDecodeOptions.defaults
        val file = File.createTempFile("decode-options", ".svga", context.cacheDir).apply { writeBytes(fixture()) }
        try {
            for (memoryBytes in listOf(0L, 32L * 1024 * 1024)) {
                SvgaEngine(context, memoryBytes = memoryBytes).use { engine ->
                    val request = SvgaRequest(SvgaSource.LocalFile(file), 1000, 1000)
                    SvgaDecodeOptions.defaults = SvgaDecodeOptions(Bitmap.Config.RGB_565, true)
                    val lowRam = engine.acquire(request)
                    assertEquals(Bitmap.Config.RGB_565, images(lowRam).getValue("visible").config)
                    assertFalse(images(lowRam).containsKey("hidden"))
                    val highRequest = request.copy(bitmapConfig = Bitmap.Config.ARGB_8888, skipInvisibleImages = false)
                    val high = engine.acquire(highRequest)
                    assertNotSame(lowRam, high)
                    assertEquals(Bitmap.Config.ARGB_8888, images(high).getValue("visible").config)
                    assertTrue(images(high).containsKey("hidden"))
                    assertSame(lowRam, engine.acquire(request))
                    SvgaDecodeOptions.defaults = SvgaDecodeOptions()
                    assertSame(high, engine.acquire(request))
                    assertEquals(2L, engine.decodeCount.get())
                }
            }
        } finally { SvgaDecodeOptions.defaults = previous; file.delete() }
    }

    @Test fun differingDecodeOptionsShareDownloadButNotDecode() = runBlocking {
        val server = MockWebServer(); server.start()
        val engine = SvgaEngine(context)
        try {
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                .setBody(Buffer().write(fixture())).setBodyDelay(300, TimeUnit.MILLISECONDS))
            val request = SvgaRequest(server.url("/decode-options.svga").toString()).copy(width = 1000, height = 1000,
                bitmapConfig = Bitmap.Config.ARGB_8888, skipInvisibleImages = false)
            val a = async { engine.acquire(request) }
            val b = async { engine.acquire(request.copy(bitmapConfig = Bitmap.Config.RGB_565, skipInvisibleImages = true)) }
            assertNotSame(a.await(), b.await())
            assertEquals(1, server.requestCount)
            assertEquals(2L, engine.decodeCount.get())
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun legacyEntityUsesTheSameDefaultsAndCanOverrideBeforeImageLoading() = runBlocking {
        val previous = SvgaDecodeOptions.defaults
        val dir = File(context.cacheDir, "legacy-bitmap-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            ZipInputStream(fixture().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    File(dir, entry.name).outputStream().use { zip.copyTo(it) }
                    zip.closeEntry()
                }
            }
            SvgaDecodeOptions.defaults = SvgaDecodeOptions(Bitmap.Config.RGB_565, true)
            val spec = JSONObject(File(dir, "movie.spec").readText())
            val default = SVGAVideoEntity(spec, dir, 1000, 1000)
            val custom = SVGAVideoEntity(spec, dir, 1000, 1000).apply { decodeOptions = SvgaDecodeOptions() }
            lateinit var view: android.view.View
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view = android.view.View(context) }
            try {
                default.parserImages(view); custom.parserImages(view)
                assertNull(default.getImageSizeByKey("hidden"))
                assertNotNull(custom.getImageSizeByKey("hidden"))
                assertEquals(600, default.getImageSizeByKey("visible")!!.width)
            } finally { default.clear(); custom.clear() }
        } finally { SvgaDecodeOptions.defaults = previous; dir.deleteRecursively() }
    }

    @Test fun filteringRealAssetsPreservesAllFramesIncludingBitmapMattes() = runBlocking {
        SvgaEngine(context).use { engine ->
            for (asset in listOf("Castle.svga", "matteBitmap.svga", "matteRect.svga")) {
                val request = SvgaRequest(SvgaSource.Asset(asset), 320, 320,
                    bitmapConfig = Bitmap.Config.ARGB_8888, skipInvisibleImages = false)
                val all = engine.acquire(request)
                val filtered = engine.acquire(request.copy(skipInvisibleImages = true))
                if (asset == "Castle.svga") assertTrue(filtered.sizeBytes < all.sizeBytes)
                val expected = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                try {
                    all.newRenderer().use { a -> filtered.newRenderer().use { b ->
                        for (frame in 0 until all.frames) {
                            expected.eraseColor(Color.TRANSPARENT); actual.eraseColor(Color.TRANSPARENT)
                            a.draw(Canvas(expected), 320, 320, frame, 0)
                            b.draw(Canvas(actual), 320, 320, frame, 0)
                            assertTrue("$asset frame $frame", expected.sameAs(actual))
                        }
                    } }
                } finally { expected.recycle(); actual.recycle() }
            }
        }
    }
}
