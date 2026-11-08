package com.opensource.svgaplayer.glide5

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.widget.ImageView
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.loader.*
import com.opensource.svgaplayer.proto.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.DeflaterOutputStream
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SvgaProgressiveLoadingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = temporary.root
        }
    }
    private lateinit var engine: SvgaEngine
    private lateinit var loader: SvgaImageLoader
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        Glide.tearDown()
        engine = SvgaEngine(context)
        loader = SvgaImageLoader(RuntimeEnvironment.getApplication(), engine)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun tearDown() {
        scope.cancel(); loader.close(); engine.close()
        shadowOf(Looper.getMainLooper()).idle()
        Glide.tearDown()
    }

    private fun fixture(binary: Boolean = false, corruptLate: Boolean = false, singleUseOnly: Boolean = false): ByteArray {
        val uses = if (singleUseOnly) linkedMapOf("first" to setOf(0), "next" to setOf(1), "late" to setOf(5))
        else linkedMapOf("first" to setOf(0), "repeat" to setOf(0, 4, 7),
            "next" to setOf(1, 2), "late" to setOf(5, 6), "mask.matte" to (0..7).toSet())
        val spec = JSONObject().put("movie", JSONObject().put("viewBox",
            JSONObject().put("width", 65).put("height", 63)).put("fps", 4).put("frames", 8))
            .put("images", JSONObject(uses.keys.associateWith { "$it.png" }))
            .put("sprites", JSONArray(uses.map { (key, frames) -> JSONObject().put("imageKey", key)
                .put("frames", JSONArray((0..7).map { frame -> JSONObject()
                    .put("alpha", if (key.endsWith(".matte")) 0 else if (frame in frames) 1 else 0)
                    .put("layout", JSONObject().put("width", 65).put("height", 63))
                    .put("transform", JSONObject().put("a", 1).put("d", 1)) })) }))
        val images = uses.keys.mapIndexed { index, key ->
            val bitmap = Bitmap.createBitmap(65, 63, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.argb(160 + index * 15, index * 40, 120, 255 - index * 40))
            }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            if (corruptLate && key == "late") {
                var offset = 8
                while (offset + 12 <= bytes.size) {
                    val length = ByteBuffer.wrap(bytes, offset, 4).int
                    if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "IDAT") {
                        bytes[offset + 8] = (bytes[offset + 8].toInt() xor 0xff).toByte(); break
                    }
                    offset += length + 12
                }
            }
            key to bytes
        }.toMap()
        if (binary) {
            val sprites = uses.map { (key, frames) -> SpriteEntity(key, (0..7).map { frame ->
                FrameEntity(if (key.endsWith(".matte")) 0f else if (frame in frames) 1f else 0f,
                    Layout(0f, 0f, 65f, 63f), Transform(1f, 0f, 0f, 1f, 0f, 0f), "", emptyList())
            }, "") }
            val movie = MovieEntity("2.0", MovieParams(65f, 63f, 4, 8),
                images.mapValues { it.value.toByteString() }, sprites, emptyList())
            return ByteArrayOutputStream().also { output ->
                DeflaterOutputStream(output).use { it.write(MovieEntity.ADAPTER.encode(movie)) }
            }.toByteArray()
        }
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec.toString().toByteArray()); zip.closeEntry()
                images.forEach { (key, bytes) ->
                    zip.putNextEntry(ZipEntry("$key.png")); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
    }
    private fun file(name: String = "gift.svga") = temporary.newFile(name).apply { writeBytes(fixture()) }
    private fun request(file: File) = SvgaRequest(SvgaSource.LocalFile(file), 65, 63, progressive = true)
    private fun drawable(resource: SvgaResource) = SVGADrawable(resource.newVideoEntity()).apply {
        scaleType = ImageView.ScaleType.FIT_XY; showStaticFrame()
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(value)
    private fun shared(entity: SVGAVideoEntity) = checkNotNull(field(entity, "sharedImages"))
    private fun pixels(entity: SVGAVideoEntity, key: String): Bitmap? = shared(entity).let {
        it.javaClass.getDeclaredMethod("bitmap", String::class.java).invoke(it, key) as Bitmap?
    }
    private fun manager(entity: SVGAVideoEntity) = checkNotNull(field(entity, "deferredImages"))
    private fun <T> await(task: Deferred<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue("Background operation did not finish", task.isCompleted)
        return runBlocking { task.await() }
    }

    @Test fun repeatedImagesStayLazyAndAllFramesMatchEagerRenderingAfterStagingIsDeleted() = runBlocking {
        val source = file()
        val progressive = engine.acquire(request(source))
        val eager = engine.acquire(request(source).copy(progressive = false))
        val streamed = drawable(progressive); val reference = drawable(eager)
        val weight = progressive.sizeBytes
        val actual = Bitmap.createBitmap(65, 63, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(65, 63, Bitmap.Config.ARGB_8888)
        try {
            assertNull(pixels(streamed.videoItem, "repeat"))
            assertNull(pixels(streamed.videoItem, "late"))
            assertEquals(65, streamed.videoItem.getImageSizeByKey("late")!!.width)
            streamed.prepareFrame(0)
            assertNotNull(pixels(streamed.videoItem, "repeat"))
            assertNotNull("Transparent matte references must be prepared conservatively", pixels(streamed.videoItem, "mask"))
            assertNull(pixels(streamed.videoItem, "next"))
            assertNull(pixels(streamed.videoItem, "late"))
            val first = pixels(streamed.videoItem, "repeat")!!
            for (frame in (0..7).toList() + (7 downTo 0).toList() + listOf(5, 0, 7, 1)) {
                expected.eraseColor(0); actual.eraseColor(0)
                reference.advanceExternalClock(frame, 0); streamed.advanceExternalClock(frame, 0)
                streamed.prepareFrame(frame)
                reference.draw(Canvas(expected)); streamed.draw(Canvas(actual))
                assertTrue("frame=$frame", expected.sameAs(actual))
            }
            assertSame("Looping and seeking reuse the decoded shared image", first, pixels(streamed.videoItem, "repeat"))
            assertEquals("LRU weight must not grow behind the cache's back", weight, progressive.sizeBytes)
            assertTrue(progressive.sizeBytes >= streamed.videoItem.imageMapSize())
        } finally { streamed.clear(); reference.clear(); actual.recycle(); expected.recycle() }
    }

    @Test fun sharedPixelsSurviveClosingOtherViewsAndClearingStrongOrWeakCaches() = runBlocking {
        val source = file()
        for (memoryBytes in listOf(0L, 1024L * 1024)) {
            SvgaEngine(context, memoryBytes = memoryBytes).use { cache ->
                val resource = cache.acquire(request(source))
                val first = drawable(resource); val second = drawable(cache.acquire(request(source)))
                try {
                    assertEquals(1L, cache.decodeCount.get())
                    coroutineScope { listOf(first, second).map { async(Dispatchers.Default) { it.awaitFrame(0) } }.awaitAll() }
                    val bitmap = pixels(first.videoItem, "repeat")!!
                    assertSame(bitmap, pixels(second.videoItem, "repeat"))
                    first.clear(); cache.clearMemory()
                    assertFalse(bitmap.isRecycled)
                    second.prepareFrame(0)
                    assertSame(bitmap, pixels(second.videoItem, "repeat"))
                    assertNotSame(resource, cache.acquire(request(source)))
                    assertFalse(bitmap.isRecycled)
                } finally { first.clear(); second.clear() }
            }
        }
    }

    @Test fun firstFramePreparationDoesNotWaitForLookaheadAndCloseCancelsItsWorker() = runBlocking {
        val resource = engine.acquire(request(file()))
        val presentation = drawable(resource)
        val store = shared(presentation.videoItem)
        val next = (field(store, "entries") as Map<*, *>)["next"]!!
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = Thread { synchronized(next) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        blocker.start()
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            presentation.configureFramePrefetch(0, 7)
            withTimeout(2_000) { withContext(Dispatchers.Default) { presentation.prepare(65, 63) } }
            assertTrue(presentation.isFrameReady(0))
            assertFalse(presentation.isFrameReady(1))
            val worker = (field(manager(presentation.videoItem), "prefetchScope") as CoroutineScope).coroutineContext[Job]!!
            val first = pixels(presentation.videoItem, "first")!!
            presentation.clear()
            assertTrue(worker.isCancelled)
            release.countDown(); withTimeout(5_000) { worker.join() }
            assertFalse("Resource-owned images cannot be recycled by a View", first.isRecycled)
            val second = drawable(resource)
            try {
                assertNull("Cancelled prefetch cannot publish its queued decode", pixels(second.videoItem, "next"))
                second.awaitFrame(1)
                assertTrue(second.isFrameReady(1))
            } finally { second.clear() }
        } finally { release.countDown(); blocker.join(); presentation.clear() }
    }

    @Test fun lruReservesFuturePixelsAndSeparatesDecodeModes() = runBlocking {
        val firstFile = file("first.svga"); val secondFile = file("second.svga")
        val weight = engine.acquire(request(firstFile)).sizeBytes
        SvgaEngine(context, memoryBytes = weight + 1, weakMemoryCacheEnabled = false).use { cache ->
            val first = cache.acquire(request(firstFile))
            val second = cache.acquire(request(secondFile))
            assertSame(second, cache.acquire(request(secondFile)))
            assertNotSame("Even undecoded pixels must count toward LRU capacity", first, cache.acquire(request(firstFile)))
            assertEquals(3L, cache.decodeCount.get())
        }
        val request = request(firstFile)
        val progressive = engine.acquire(request)
        val eager = engine.acquire(request.copy(progressive = false))
        val pooled = engine.acquire(request.copy(inBitmap = true))
        assertNotSame(progressive, eager); assertNotSame(progressive, pooled)
        assertSame(progressive, engine.acquire(request))
        assertSame(pooled, engine.acquire(request.copy(inBitmap = true)))
    }

    @Test fun animationsMadeEntirelyOfPooledImagesAlsoStartWithoutWaitingForPrefetch() = runBlocking {
        val source = temporary.newFile("only-single-use.svga").apply { writeBytes(fixture(singleUseOnly = true)) }
        val presentation = drawable(engine.acquire(request(source).copy(inBitmap = true)))
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        presentation.configureFramePrefetch(0, 7)
        presentation.awaitFrame(0)
        val decodeLock = field(manager(presentation.videoItem), "decodeLock")!!
        val blocker = Thread { synchronized(decodeLock) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        blocker.start()
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { withContext(Dispatchers.Default) { presentation.prepare(65, 63) } }
            assertTrue(presentation.isFrameReady(0))
            assertFalse(presentation.isFrameReady(1))
        } finally { presentation.clear(); release.countDown(); blocker.join() }
    }

    @Test fun poolingKeepsMutablePixelsPrivateAndRepeatedPixelsShared() = runBlocking {
        val resource = engine.acquire(request(file()).copy(inBitmap = true))
        val first = drawable(resource); val second = drawable(resource)
        try {
            first.awaitFrame(0); second.awaitFrame(0)
            assertSame(pixels(first.videoItem, "repeat"), pixels(second.videoItem, "repeat"))
            fun privatePixels(drawable: SVGADrawable) = (field(manager(drawable.videoItem), "bitmaps") as Map<*, *>)["first"]!!
            val a = field(privatePixels(first), "bitmap") as Bitmap
            val b = field(privatePixels(second), "bitmap") as Bitmap
            assertNotSame(a, b)
            first.clear()
            assertFalse(b.isRecycled)
            assertFalse(pixels(second.videoItem, "repeat")!!.isRecycled)
        } finally { first.clear(); second.clear() }
    }

    @Test fun deflatedBinaryKeepsRepeatedImagesLazyAndReversePreparationUsesItsActualStartFrame() = runBlocking {
        val source = temporary.newFile("binary.svga").apply { writeBytes(fixture(binary = true)) }
        val presentation = drawable(engine.acquire(request(source)))
        try {
            presentation.configureFramePrefetch(2, 6, true)
            presentation.advanceExternalClock(6, 0)
            withContext(Dispatchers.Default) { presentation.prepare(65, 63) }
            assertTrue(presentation.isFrameReady(6))
            assertNotNull(pixels(presentation.videoItem, "late"))
            assertNull("Preparing reverse playback must not decode frame zero", pixels(presentation.videoItem, "first"))
            presentation.awaitFrame(2)
            assertTrue(presentation.isFrameReady(2))
        } finally { presentation.clear() }
    }

    @Test fun noStoreResponsesCannotLeakThroughEitherMemoryCache() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setHeader("Cache-Control", "no-store")
                .setBody(Buffer().write(fixture()))) }
            val request = SvgaRequest(server.url("/private.svga").toString()).copy(progressive = true)
            val first = engine.acquire(request)
            val presentation = drawable(first)
            try {
                presentation.awaitFrame(0)
                assertNotSame(first, engine.acquire(request))
                assertEquals(2, server.requestCount)
                assertTrue(runCatching { engine.acquire(request.copy(cacheOnly = true)) }.isFailure)
            } finally { presentation.clear() }
        }
    }

    @Test fun lateDecodeFailureEvictsTheBadBodyFromMemoryAndDisk() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                .setBody(Buffer().write(fixture(corruptLate = true))))
            val request = SvgaRequest(server.url("/broken.svga").toString()).copy(progressive = true)
            val broken = engine.acquire(request)
            val presentation = drawable(broken)
            try {
                presentation.awaitFrame(0)
                assertTrue("Failure must be discovered when the later frame is decoded",
                    runCatching { presentation.awaitFrame(5) }.isFailure)
                assertTrue(broken.hasImageDecodeError)
                awaitEngineWorkers()
                assertTrue(runCatching { engine.acquire(request.copy(cacheOnly = true)) }.isFailure)
                server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                    .setBody(Buffer().write(fixture())))
                val repaired = engine.acquire(request)
                assertNotSame(broken, repaired)
                val next = drawable(repaired)
                try { next.awaitFrame(5); assertTrue(next.isFrameReady(5)) }
                finally { next.clear() }
                assertSame(repaired, engine.acquire(request.copy(cacheOnly = true)))
                assertEquals(2, server.requestCount)
            } finally { presentation.clear() }
        }
    }

    @Test fun failingAnOldPresentationCannotInvalidateTheRefreshedBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                .setBody(Buffer().write(fixture(corruptLate = true))))
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                .setBody(Buffer().write(fixture())))
            val request = SvgaRequest(server.url("/refresh.svga").toString()).copy(progressive = true)
            val old = drawable(engine.acquire(request))
            try {
                val fresh = engine.acquire(request.copy(refresh = true))
                assertTrue(runCatching { old.awaitFrame(5) }.isFailure)
                awaitEngineWorkers()
                assertSame(fresh, engine.acquire(request.copy(cacheOnly = true)))
                engine.clearMemory()
                val fromDisk = engine.acquire(request.copy(cacheOnly = true))
                assertFalse(fromDisk.hasImageDecodeError)
                val restored = drawable(fromDisk)
                try { restored.awaitFrame(5); assertTrue(restored.isFrameReady(5)) }
                finally { restored.clear() }
                assertEquals(2, server.requestCount)
            } finally { old.clear() }
        }
    }

    private suspend fun awaitEngineWorkers() {
        val job = (field(engine, "scope") as CoroutineScope).coroutineContext[Job]!!
        withTimeout(5_000) { job.children.toList().forEach { it.join() } }
    }

    @Test fun viewDefaultsToProgressiveAndOptOutReplacesTheRequest() {
        val source = file()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = SVGAImageView(activity.get())
            val root = FrameLayout(activity.get())
            activity.get().setContentView(root)
            root.addView(view, FrameLayout.LayoutParams(65, 63)); view.layout(0, 0, 65, 63)
            val ready = CompletableDeferred<Unit>()
            val first = view.loadSvga(source) {
                loader = this@SvgaProgressiveLoadingTest.loader; autoPlay = false
                onReady = { ready.complete(Unit) }; onError = { ready.completeExceptionally(it) }
            }
            await(scope.async { ready.await() })
            val drawable = view.drawable as SVGADrawable
            assertTrue(drawable.isFrameReady(0))
            assertNotNull(pixels(drawable.videoItem, "first"))
            assertNull("Images beyond the prefetch window stay lazy", pixels(drawable.videoItem, "late"))
            assertSame(first, view.loadSvga(source) { loader = this@SvgaProgressiveLoadingTest.loader; autoPlay = false })
            val eagerReady = CompletableDeferred<Unit>()
            val second = view.loadSvga(source) {
                loader = this@SvgaProgressiveLoadingTest.loader; autoPlay = false; progressive = false
                onReady = { eagerReady.complete(Unit) }; onError = { eagerReady.completeExceptionally(it) }
            }
            assertNotSame(first, second)
            await(scope.async { eagerReady.await() })
            assertNull(field((view.drawable as SVGADrawable).videoItem, "sharedImages"))
            view.clearSvga()
        } finally { activity.pause().stop().destroy() }
    }
}
