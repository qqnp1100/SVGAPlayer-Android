package com.opensource.svgaplayer.loader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.SvgaDecodeOptions
import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgaRenderScaleTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun spec(): JSONObject {
        fun frame(alpha: Int) = JSONObject().put("alpha", alpha)
            .put("layout", JSONObject().put("width", 400).put("height", 200))
            .put("transform", JSONObject().put("a", 1).put("d", 1))
        fun sprite(key: String, lastAlpha: Int) = JSONObject().put("imageKey", key)
            .put("frames", JSONArray().put(frame(1)).put(frame(lastAlpha)))
        return JSONObject().put("movie", JSONObject()
            .put("viewBox", JSONObject().put("width", 400).put("height", 200))
            .put("fps", 20).put("frames", 2))
            .put("images", JSONObject().put("shared", "image.png").put("single", "image.png"))
            .put("sprites", JSONArray().put(sprite("shared", 1)).put(sprite("single", 0)))
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun fixture(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec().toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("image.png")); zip.write(png()); zip.closeEntry()
        }
    }.toByteArray()

    private fun assertSize(video: SVGAVideoEntity, width: Int, height: Int) {
        for (key in listOf("shared", "single")) {
            val size = requireNotNull(video.getImageSizeByKey(key))
            assertEquals("$key width", width, size.width)
            assertEquals("$key height", height, size.height)
        }
    }

    private fun assertPreparedSize(resource: SvgaResource, width: Int, height: Int) {
        val drawable = SVGADrawable(resource.newVideoEntity())
        try {
            // Keep native ImageDecoder calls on Robolectric's sandbox thread; its JNI shim is thread-bound.
            drawable.prepareFrame(0)
            assertSize(drawable.videoItem, width, height)
        } finally { drawable.clear() }
    }

    private suspend fun verifyModes() {
        val bytes = fixture()
        val expected = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            for (progressive in listOf(false, true)) for (inBitmap in listOf(false, true)) {
                val resource = SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), 200, 100,
                    // Full-sized targets require 160,000 bytes; half-sized targets fit in 40,000.
                    maxDecodedBytes = 40_000,
                    decodeOptions = SvgaDecodeOptions(renderScale = .5f, progressive = progressive, inBitmap = inBitmap))
                assertEquals(400, resource.width); assertEquals(200, resource.height)
                val drawable = SVGADrawable(resource.newVideoEntity()).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setBounds(0, 0, 200, 100)
                    showStaticFrame()
                }
                val actual = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
                try {
                    drawable.prepareFrame(0)
                    assertTrue(drawable.isFrameReady(0))
                    assertSize(drawable.videoItem, 100, 50)
                    drawable.draw(Canvas(actual))
                    assertTrue("Render bounds changed: progressive=$progressive inBitmap=$inBitmap", expected.sameAs(actual))
                    assertFalse(resource.hasImageDecodeError)
                } finally { drawable.clear(); actual.recycle() }
            }
        } finally { expected.recycle() }
    }

    @Test fun imageDecoderAndDeferredModesUseScaledDimensionsAndBudgets() = runBlocking { verifyModes() }

    @Test fun bitmapFactoryUsesScaledDimensionsAndBudgets() = runBlocking {
        // Initialize native graphics before exercising the pre-28 production branch.
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).recycle()
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
        try { verifyModes() }
        finally { ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk) }
    }

    @Test fun onePreservesExistingPixelsAndHalfAlsoScalesUnknownOrOversizedViewports() = runBlocking {
        val bytes = fixture()
        for ((width, height, expectedWidth) in listOf(Triple(200, 100, 200), Triple(0, 0, 800), Triple(1600, 800, 800))) {
            val full = SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), width, height,
                decodeOptions = SvgaDecodeOptions()).newVideoEntity()
            val half = SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), width, height,
                decodeOptions = SvgaDecodeOptions(renderScale = .5f)).newVideoEntity()
            try {
                assertSize(full, expectedWidth, expectedWidth / 2)
                assertSize(half, expectedWidth / 2, expectedWidth / 4)
                assertEquals(full.imageMapSize(), half.imageMapSize() * 4)
            } finally { full.clear(); half.clear() }
        }
    }

    @Test fun globalDefaultsAreCapturedOnceAndExplicitOverridesHaveSeparateStrongAndWeakCaches() = runBlocking {
        val defaults = SvgaDecodeOptions.defaults
        val bytes = fixture()
        try {
            for (strong in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(Buffer().write(bytes)))
                    SvgaEngine(RuntimeEnvironment.getApplication()).use { engine ->
                        SvgaDecodeOptions.defaults = defaults.copy(renderScale = .5f)
                        val request = SvgaRequest(server.url("/scale-$strong.svga").toString()).copy(
                            width = 200, height = 100, memoryCache = strong, weakMemoryCache = !strong, progressive = true)
                        val half = engine.acquire(request) {
                            // A concurrent global change must not alter this in-flight request.
                            SvgaDecodeOptions.defaults = defaults.copy(renderScale = .25f)
                        }
                        val quarter = engine.acquire(request)
                        val full = engine.acquire(request.copy(renderScale = 1f))
                        assertNotSame(half, quarter); assertNotSame(half, full)
                        assertPreparedSize(half, 100, 50)
                        assertPreparedSize(quarter, 50, 25)
                        assertPreparedSize(full, 200, 100)
                        assertSame(half, engine.acquire(request.copy(renderScale = .5f)))
                        assertSame(quarter, engine.acquire(request))
                        assertEquals(3, engine.decodeCount.get())
                        assertEquals(1, server.requestCount)
                    }
                }
            }
        } finally { SvgaDecodeOptions.defaults = defaults }
    }

    @Test fun concurrentScalesShareDownloadButOnlyMatchingScalesShareDecode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                .setBodyDelay(150, TimeUnit.MILLISECONDS).setBody(Buffer().write(fixture())))
            SvgaEngine(RuntimeEnvironment.getApplication()).use { engine ->
                val request = SvgaRequest(server.url("/concurrent.svga").toString()).copy(width = 200, height = 100, progressive = true)
                val first = async { engine.acquire(request.copy(renderScale = .5f)) }
                val same = async { engine.acquire(request.copy(renderScale = .5f)) }
                val other = async { engine.acquire(request.copy(renderScale = 1f)) }
                assertSame(first.await(), same.await()); assertNotSame(first.await(), other.await())
                assertEquals(2, engine.decodeCount.get()); assertEquals(1, server.requestCount)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun legacyEntitiesInheritGlobalScaleAndAllowPerLoadOverride() = runBlocking {
        val defaults = SvgaDecodeOptions.defaults
        val directory = temporary.newFolder()
        File(directory, "image.png").writeBytes(png())
        try {
            SvgaDecodeOptions.defaults = defaults.copy(renderScale = .5f)
            for (scale in listOf(null, 1f, .25f)) {
                val video = SVGAVideoEntity(spec(), directory, 200, 100)
                try {
                    if (scale != null) video.decodeOptions = defaults.copy(renderScale = scale)
                    video.parserImages(View(RuntimeEnvironment.getApplication()))
                    val width = (200 * (scale ?: .5f)).toInt()
                    assertSize(video, width, width / 2)
                } finally { video.clear() }
            }
        } finally { SvgaDecodeOptions.defaults = defaults }
    }

    @Test fun invalidScalesFailBeforeDecode() {
        for (scale in listOf(0f, -1f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { SvgaDecodeOptions(renderScale = scale) }
            assertThrows(IllegalArgumentException::class.java) {
                SvgaRequest(SvgaSource.Asset("test.svga"), renderScale = scale).resolveDecodeOptions()
            }
        }
        assertEquals(1f, SvgaDecodeOptions().renderScale)
        assertEquals(Float.MIN_VALUE, SvgaDecodeOptions(renderScale = Float.MIN_VALUE).renderScale)
    }
}
