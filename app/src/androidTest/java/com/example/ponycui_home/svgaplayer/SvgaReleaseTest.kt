package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SvgaReleaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val asset = SvgaSource.Asset("rose_2.0.0.svga")
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(target)

    @Test fun clearedEntityDropsResourceWithoutRecyclingAnotherPresentation() = runBlocking {
        val engine = SvgaEngine(context)
        try {
            val resource = engine.acquire(SvgaRequest(asset, 128, 128))
            val first = resource.newVideoEntity()
            val second = resource.newVideoEntity()
            val images = field(second, "imageMap") as Map<*, *>
            val pixels = images.values.first() as Bitmap
            first.clear(); engine.clearMemory()
            assertNull(field(first, "resource"))
            assertTrue((field(first, "imageMap") as Map<*, *>).isEmpty())
            assertSame(resource, field(second, "resource"))
            assertFalse(pixels.isRecycled)
            second.clear()
            assertNull(field(second, "resource"))
        } finally { engine.close() }
    }

    @Test fun legacyBitmapFinishingAfterClearIsDisposedInsteadOfPublished() = runBlocking {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val previous = SVGAParser.getBitmapDecoder()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val directory = File(context.cacheDir, "release-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(directory, "image.png").writeBytes(byteArrayOf(1))
        val entity = SVGAVideoEntity(JSONObject("""{"movie":{"viewBox":{"width":16,"height":16},"fps":12,"frames":1},"images":{"image":"image.png"},"sprites":[]}"""), directory)
        SVGAParser.setBitmapDecoder(object : SVGAParser.BitmapDecoder by previous {
            override fun onLoad(imageView: View, path: String, scaleX: Float, scaleY: Float,
                                frameWidth: Int, frameHeight: Int, videoWidth: Int, videoHeight: Int): Bitmap {
                entered.countDown()
                check(finish.await(10, TimeUnit.SECONDS))
                return bitmap
            }
        })
        try {
            val decoding = async(Dispatchers.Default) { entity.parserImages(View(context)) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                entity.clear()
            } finally { finish.countDown() }
            decoding.await()
            assertTrue(bitmap.isRecycled)
            assertTrue((field(entity, "imageMap") as Map<*, *>).isEmpty())
        } finally {
            finish.countDown(); SVGAParser.setBitmapDecoder(previous); entity.clear(); directory.deleteRecursively()
        }
    }

    @Test fun differentMemoryPoliciesShareTransferAndDecodeButKeepTheirOwnCaches() = runBlocking {
        val server = MockWebServer(); server.start()
        val engine = SvgaEngine(context)
        try {
            val bytes = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
            repeat(3) { server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600")
                .setBody(Buffer().write(bytes)).setBodyDelay(400, TimeUnit.MILLISECONDS)) }
            val base = SvgaRequest(SvgaSource.Remote(server.url("/policies.svga").toString()), 128, 128)
            val requests = listOf(base.copy(memoryCache = false, weakMemoryCache = false),
                base.copy(memoryCache = true, weakMemoryCache = false), base.copy(memoryCache = false, weakMemoryCache = true))
            val resources = requests.map { request -> async { engine.acquire(request) } }.awaitAll()
            assertEquals(1, server.requestCount)
            assertEquals(1L, engine.decodeCount.get())
            resources.forEach { assertSame(resources[0], it) }
            assertSame(resources[0], engine.acquire(requests[1].copy(cacheOnly = true)))
            assertSame(resources[0], engine.acquire(requests[2].copy(cacheOnly = true)))
            assertEquals(1L, engine.memoryHits.get()); assertEquals(1L, engine.weakMemoryHits.get())
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun clearedDynamicLoadCannotOverwriteAReusedEntity() = runBlocking {
        val previous = SVGAParser.customDynamicImageLoad
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val old = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val current = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val dynamic = SVGADynamicEntity()
        SVGAParser.customDynamicImageLoad = object : SVGAParser.CustomDynamicImageLoad {
            override suspend fun loadImage(imageView: android.widget.ImageView, url: String, forKey: String): Bitmap {
                entered.complete(Unit); finish.await(); return old
            }
        }
        try {
            dynamic.setDynamicImage("https://unused.test/image", "image")
            val loading = async { dynamic.requestDynamicImage(android.widget.ImageView(context)) }
            entered.await()
            dynamic.clearDynamicObjects()
            dynamic.setDynamicImage(current, "image")
            finish.complete(Unit); loading.await()
            assertSame(current, dynamic.getDynamicImage("image"))
            assertFalse(old.isRecycled)
            dynamic.clearDynamicObjects()
            assertNull(dynamic.getDynamicImage("image")); assertFalse(current.isRecycled)
        } finally {
            finish.complete(Unit); SVGAParser.customDynamicImageLoad = previous
            dynamic.clearDynamicObjects(); old.recycle(); current.recycle()
        }
    }

    @Test fun viewControlsResumeKeepsTheSeekPosition() {
        val ready = CountDownLatch(1)
        lateinit var view: SVGAImageView
        lateinit var handle: SvgaViewHandle
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = SVGAImageView(activity); activity.setContentView(view)
                handle = view.loadSvga(asset) { useViewControls = true; autoPlay = false; onReady = { ready.countDown() } }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            scenario.onActivity {
                view.stepToFrame(5, false)
                handle.pause(); handle.resume()
                val session = checkNotNull(field(view, "modernPlayback"))
                assertEquals(5, (field(session, "clock") as SvgaPlayback).frame)
                handle.close()
            }
        }
    }

    @Test fun updatedBitmapSurvivesReattachAndClosedHandleDropsBindings() {
        val ready = CountDownLatch(1); val reattached = CountDownLatch(1)
        val first = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        var count = 0
        lateinit var view: SVGAImageView
        lateinit var root: android.widget.FrameLayout
        lateinit var handle: SvgaViewHandle
        var failure: Throwable? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                root = android.widget.FrameLayout(activity); view = SVGAImageView(activity)
                root.addView(view, android.widget.FrameLayout.LayoutParams(128, 128)); activity.setContentView(root)
                handle = view.loadSvga(asset) {
                    staticImage = true; restartOnAttach = true
                    bindings = svgaBindings { image("replacement", first) }
                    onReady = { if (++count == 1) ready.countDown() else reattached.countDown() }
                    onError = { failure = it; ready.countDown(); reattached.countDown() }
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                handle.updateBindings(svgaBindings { image("replacement", second) })
                root.removeView(view); root.addView(view)
            }
            assertTrue(reattached.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                assertSame(second, (view.drawable as SVGADrawable).dynamicItem.getDynamicImage("replacement"))
                assertFalse(first.isRecycled); assertFalse(second.isRecycled)
                handle.close()
                assertNull(view.drawable)
                assertSame(SvgaBindings.Empty, (field(handle, "options") as SvgaViewOptions).bindings)
                assertNull(field(handle, "resource")); assertNull(field(handle, "identity"))
                assertNull(field(handle, "targetView"))
                // A retained closed handle must not control a replacement presentation.
                view.setVideoItem(kotlinx.coroutines.runBlocking { SvgaImageLoader.get(context).load(SvgaRequest(asset, 128, 128)) }.newVideoEntity())
                view.stepToFrame(5, false)
                handle.seekToProgress(0f)
                assertEquals(5, (view.drawable as SVGADrawable).currentFrame)
                view.clear()
            }
        }
        first.recycle(); second.recycle()
    }

    @Test fun failedBindingDoesNotKeepBaseBitmapsOnTheView() {
        val failed = CountDownLatch(1)
        lateinit var handle: SvgaViewHandle
        lateinit var view: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = SVGAImageView(activity); activity.setContentView(view)
                handle = view.loadSvga(asset) {
                    bindings = svgaBindings { image("bad", Any()) }
                    onError = { failed.countDown() }
                }
            }
            assertTrue(failed.await(15, TimeUnit.SECONDS))
            scenario.onActivity {
                assertNull(view.drawable); assertNull(field(handle, "resource"))
                assertNull(field(handle, "audio")); handle.close()
            }
        }
    }
}
