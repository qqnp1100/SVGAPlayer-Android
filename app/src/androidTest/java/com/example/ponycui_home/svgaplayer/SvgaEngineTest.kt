package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.SvgaAudioSession
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class SvgaEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample() = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
    private fun response(cache: String = "max-age=3600") = MockResponse().setHeader("Cache-Control", cache).setBody(Buffer().write(sample()))

    @Test fun downloadProgressKnownUnknownLengthAndCache() = runBlocking {
        val server = MockWebServer(); server.start()
        val engine = SvgaEngine(context)
        val loader = com.opensource.svgaplayer.coil3.SvgaImageLoader(context, engine)
        try {
            val bytes = sample()
            server.enqueue(response().throttleBody(8192, 10, java.util.concurrent.TimeUnit.MILLISECONDS))
            val request = SvgaRequest(SvgaSource.Remote(server.url("/progress.svga").toString()))
            val events = mutableListOf<SvgaDownloadProgress>()
            loader.load(request) { events.add(it) }
            assertTrue(events.isNotEmpty())
            assertEquals(bytes.size.toLong(), events.last().bytesRead)
            assertEquals(bytes.size.toLong(), events.last().totalBytes)
            assertTrue(events.last().completed)
            assertEquals(1f, events.last().fraction!!, 0f)
            assertTrue(events.zipWithNext().all { (a, b) -> a.bytesRead <= b.bytesRead })
            events.clear(); loader.load(request) { events.add(it) }
            assertTrue(events.isEmpty()); assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 4096))
            engine.acquire(request.copy(source = SvgaSource.Remote(server.url("/chunked.svga").toString()))) { events.add(it) }
            assertTrue(events.last().completed); assertNull(events.last().totalBytes)
            assertNull(events.last().fraction); assertEquals(bytes.size.toLong(), events.last().bytesRead)
            events.clear()
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(runCatching { engine.acquire(request.copy(refresh = true)) { events.add(it) } }.isFailure)
            assertTrue(events.none { it.completed })
        } finally { loader.close(); engine.close(); server.shutdown() }
    }

    @Test fun concurrentConsumersShareDownloadAndResourceThenEvictionIsSafe() = runBlocking {
        val server = MockWebServer(); server.start()
        val engine = SvgaEngine(context)
        try {
            server.enqueue(response().setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
            val request = SvgaRequest(SvgaSource.Remote(server.url("/shared.svga").toString()), 256, 256)
            val resources = (0 until 10).map { async { engine.acquire(request) } }.awaitAll()
            assertEquals(1, server.requestCount); assertEquals(1, engine.decodeCount.get())
            resources.forEach { assertSame(resources[0], it) }
            assertSame(resources[0], engine.acquire(request))
            val first = resources[0].newVideoEntity(); val second = resources[0].newVideoEntity()
            first.clear(); engine.clearMemory(); engine.clearDisk()
            assertFalse(second.isRecycleImage())
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            resources[0].newRenderer().use { it.draw(Canvas(bitmap), 256, 256, 1, 0) }
            val pixels = IntArray(256 * 256); bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
            assertTrue(pixels.any { (it ushr 24) != 0 })
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun cacheNoneAndNoStoreDoNotPersistAndFailureCanRetry() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            val request = SvgaRequest(SvgaSource.Remote(server.url("/none.svga").toString()), 128, 128, SvgaCachePolicy.NONE)
            server.enqueue(response()); server.enqueue(response())
            engine.acquire(request); engine.acquire(request)
            assertEquals(2, server.requestCount)
            val noStore = request.copy(source = SvgaSource.Remote(server.url("/no-store.svga").toString()), cachePolicy = SvgaCachePolicy.ALL)
            server.enqueue(response("no-store")); server.enqueue(response("no-store"))
            engine.acquire(noStore); engine.acquire(noStore)
            assertEquals(4, server.requestCount)
            assertTrue(runCatching { engine.acquire(noStore.copy(cacheOnly = true)) }.isFailure)
            val broken = request.copy(source = SvgaSource.Remote(server.url("/retry.svga").toString()), cachePolicy = SvgaCachePolicy.ALL)
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
            assertTrue(runCatching { engine.acquire(broken) }.isFailure)
            server.enqueue(response()); assertTrue(engine.acquire(broken).frames > 0)
            assertEquals(6, server.requestCount)
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun cancelledSubscriberDoesNotBreakOtherSizeAndConditional304Works() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            server.enqueue(response("max-age=0").setHeader("ETag", "v1").setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS))
            val request = SvgaRequest(SvgaSource.Remote(server.url("/cancel.svga").toString()), 128, 128)
            val first = async { engine.acquire(request) }
            val second = async { engine.acquire(request.copy(width = 256, height = 256)) }
            delay(100); first.cancelAndJoin(); assertTrue(second.await().frames > 0)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600"))
            engine.acquire(request)
            server.takeRequest(); assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
            engine.acquire(request); assertEquals(2, server.requestCount)
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun zipSlipRejectedAndLegacyAssetsDecode() = runBlocking {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("../escape")); it.write(byteArrayOf(1)); it.closeEntry() }
        val directory = File(context.cacheDir, "zip-slip-test").apply { mkdirs() }
        try { assertTrue(runCatching { SvgaResource.decode(ByteArrayInputStream(bytes.toByteArray()), directory) }.isFailure) }
        finally { directory.deleteRecursively() }
        val engine = SvgaEngine(context)
        try {
            listOf("rose.svga", "rose_2.0.0.svga", "matteBitmap.svga", "matteRect.svga", "mp3_to_long.svga").forEach {
                val resource = engine.acquire(SvgaRequest(SvgaSource.Asset(it), 256, 256))
                assertTrue(resource.frames > 0)
                resource.newRenderer().use { renderer -> renderer.draw(Canvas(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)), 256, 256, 0, 0) }
            }
        } finally { engine.close() }
    }

    @Test fun suppliedNetworkSamplesLoadAndRender() = runBlocking {
        val engine = SvgaEngine(context)
        try {
            for (name in listOf("head_bg_vip10", "head_bg_vip9")) {
                val start = System.nanoTime()
                val resource = engine.acquire(SvgaRequest(SvgaSource.Remote("https://pic.vchat-onlie.com/$name.svga"), 256, 256))
                Log.i("SvgaTest", "$name: ${resource.width}x${resource.height}, ${resource.frames} frames @ ${resource.fps}, ${resource.sizeBytes} bytes, ${(System.nanoTime()-start)/1_000_000} ms")
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                resource.newRenderer().use { renderer -> for (frame in 0 until resource.frames) renderer.draw(Canvas(bitmap), 256, 256, frame, frame * 1_000_000_000L / resource.fps) }
                File(context.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { engine.close() }
    }

    @Test fun nativeRendererMatchesViewAtTranslatedViewportAndAudioIsIndependent() = runBlocking {
        val engine = SvgaEngine(context)
        try {
            for (name in listOf("rose_2.0.0.svga", "matteBitmap.svga", "matteRect.svga")) {
                val resource = engine.acquire(SvgaRequest(SvgaSource.Asset(name), 256, 256))
                val reference = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val view = SVGAImageView(context)
                    val drawable = view.setStaticVideoItem(resource.newVideoEntity(), null)!!
                    drawable.scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    drawable.updateCurrentFrame(1)
                    drawable.draw(Canvas(reference)); view.clear()
                }
                val native = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(native); canvas.translate(40f, 50f)
                resource.newRenderer().use { it.draw(canvas, 256, 256, 1, 0) }
                val crop = Bitmap.createBitmap(native, 40, 50, 256, 256)
                assertTrue("Viewport rendering differs: $name", reference.sameAs(crop))
            }
            val audioResource = engine.acquire(SvgaRequest(SvgaSource.Asset("mp3_to_long.svga"), 128, 128))
            val first = SvgaAudioSession(context, audioResource)
            val second = SvgaAudioSession(context, audioResource)
            try {
                withContext(Dispatchers.IO) { first.prepare(); second.prepare() }
                first.advance(0, 0); second.advance(0, 0); first.close(); second.pause(); second.advance(1, 0)
            } finally { first.close(); second.close() }
        } finally { engine.close() }
    }

    @Test fun memoryOnlyHitsWithoutDiskAndFileVersionChangesInvalidate() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        val file = File(context.cacheDir, "source-version-test.svga")
        try {
            server.enqueue(response())
            val request = SvgaRequest(SvgaSource.Remote(server.url("/memory.svga").toString()), 128, 128, SvgaCachePolicy.MEMORY)
            val first = engine.acquire(request)
            assertSame(first, engine.acquire(request)); assertSame(first, engine.acquire(request.copy(cacheOnly = true)))
            assertEquals(1, server.requestCount)
            file.writeBytes(sample())
            val local = SvgaRequest(SvgaSource.LocalFile(file), 128, 128)
            val original = engine.acquire(local)
            file.writeBytes(context.assets.open("matteRect.svga").use { it.readBytes() })
            assertNotSame(original, engine.acquire(local))
        } finally { file.delete(); engine.close(); server.shutdown() }
    }

    @Test fun legacyParserReusesZlibAndCompletedZipCaches() = runBlocking {
        val server = MockWebServer(); server.start()
        val parser = SVGAParser(context)
        suspend fun load(url: String): SVGAVideoEntity = withTimeout(15_000) {
            suspendCancellableCoroutine { continuation ->
                val cancel = parser.decodeFromURL(java.net.URL(url), object : SVGAParser.ParseCompletion {
                    override fun onComplete(videoItem: SVGAVideoEntity) { continuation.resume(videoItem) }
                    override fun onError() { continuation.resumeWithException(IllegalStateException("Legacy parser failed")) }
                })
                continuation.invokeOnCancellation { cancel?.invoke() }
            }
        }
        try {
            for (asset in listOf("rose_2.0.0.svga", "rose.svga")) {
                server.enqueue(MockResponse().setBody(Buffer().write(context.assets.open(asset).use { it.readBytes() })))
                val url = server.url("/$asset").toString()
                val first = load(url); val second = load(url)
                assertEquals(first.frames, second.frames)
                first.clear(); second.clear()
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun staleFallbackIsOptInAndRefreshNoStoreInvalidatesPreviousCaches() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            val request = SvgaRequest(SvgaSource.Remote(server.url("/policy.svga").toString()), 128, 128)
            server.enqueue(response("max-age=0")); engine.acquire(request)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(engine.acquire(request.copy(allowStaleOnError = true)).frames > 0)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { engine.acquire(request) }.isFailure)
            server.enqueue(response("max-age=3600")); engine.acquire(request.copy(refresh = true))
            val readOnly = request.copy(memoryRead = false, memoryWrite = false, diskRead = true, diskWrite = false, cacheOnly = true)
            assertTrue(engine.acquire(readOnly).frames > 0)
            assertEquals(4, server.requestCount)
            server.enqueue(response("no-store")); engine.acquire(request.copy(refresh = true))
            assertTrue(runCatching { engine.acquire(request.copy(cacheOnly = true)) }.isFailure)
            val expired = request.copy(source = SvgaSource.Remote(server.url("/expired.svga").toString()))
            server.enqueue(response("max-age=0")); engine.acquire(expired)
            server.enqueue(response("no-store")); engine.acquire(expired)
            assertTrue(runCatching { engine.acquire(expired.copy(cacheOnly = true)) }.isFailure)
        } finally { engine.close(); server.shutdown() }
    }
}
