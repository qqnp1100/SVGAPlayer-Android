package com.opensource.svgaplayer.glide5

import android.content.ContextWrapper
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SvgaGlideLoaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = temporary.root
        }
    }
    private lateinit var engine: SvgaEngine
    private lateinit var loader: SvgaImageLoader
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val data by lazy {
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec"))
                zip.write("""{"movie":{"viewBox":{"width":100,"height":80},"fps":20,"frames":2},"images":{},"sprites":[]}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }
    private fun request(path: String = "/gift.svga") = SvgaRequest(server.url(path).toString())
    private fun response() = MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(Buffer().write(data))

    @Before fun setUp() {
        Glide.tearDown()
        server = MockWebServer().apply { start() }
        engine = SvgaEngine(context)
        loader = SvgaImageLoader(RuntimeEnvironment.getApplication(), engine)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun tearDown() {
        scope.cancel(); loader.close()
        shadowOf(Looper.getMainLooper()).idle()
        engine.close(); Glide.tearDown(); server.shutdown()
    }
    private fun <T> await(task: Deferred<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Coroutine did not complete", task.isCompleted)
        return runBlocking { task.await() }
    }
    private fun <T> runTask(block: suspend () -> T): T = await(scope.async { block() })
    private fun png(width: Int, height: Int): File {
        val file = File(temporary.root, "image-$width-$height.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test fun glideLoadsPreparedResourcesAndUsesEngineCacheAndProgress() {
        val progress = mutableListOf<SvgaDownloadProgress>()
        server.enqueue(response())
        val resource = runTask { loader.load(request()) { progress.add(it) } }
        assertEquals(2, resource.frames)
        assertEquals(100, resource.width)
        assertTrue(progress.last().completed)
        assertSame(resource, runTask { loader.load(request().copy(cacheOnly = true)) })
        assertEquals(1, server.requestCount)
        assertEquals(1, engine.decodeCount.get())
    }

    @Test fun nonePolicyBypassesGlideAndEngineCaches() {
        val request = request().copy(cachePolicy = SvgaCachePolicy.NONE)
        repeat(2) { server.enqueue(response()); runTask { loader.load(request) } }
        assertEquals(2, server.requestCount)
        assertEquals(2, engine.decodeCount.get())
    }

    @Test fun hostCacheOnlyDefaultsDoNotOverrideSvgaRequestPolicy() {
        loader.imageLoader.applyDefaultRequestOptions(RequestOptions().onlyRetrieveFromCache(true))
        server.enqueue(response())
        assertEquals(2, runTask { loader.load(request()) }.frames)
        assertEquals(1, server.requestCount)
    }

    @Test fun requiredImageFailurePropagatesAndKeepsManagerUsable() {
        val source = png(32, 16)
        val bindings = svgaBindings {
            image("avatar", source, size = 32)
            image("required", File(temporary.root, "missing.png"))
        }
        val failure = runTask { runCatching { bindings.prepare(loader.imageLoader, context, SvgaCachePolicy.NONE) } }
        assertTrue(failure.isFailure)
        // The shared Glide manager remains available after an error and target cleanup.
        val dynamic = runTask {
            svgaBindings { image("avatar", source, size = 32) }.prepare(loader.imageLoader, context, SvgaCachePolicy.NONE)
        }
        assertEquals(Color.RED, dynamic.getDynamicImage("avatar")!!.getPixel(0, 0))
        dynamic.clearDynamicObjects()
    }

    @Test fun cancellingOneSubscriberKeepsTheOtherAndItsProgress() {
        server.enqueue(response().setBodyDelay(400, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val first = scope.async { loader.load(request()) { started.complete(Unit) } }
        await(scope.async { started.await() })
        val progress = mutableListOf<SvgaDownloadProgress>()
        val secondStarted = CompletableDeferred<Unit>()
        val second = scope.async { loader.load(request()) { progress.add(it); secondStarted.complete(Unit) } }
        await(scope.async { secondStarted.await() })
        first.cancel()
        assertEquals(2, await(second).frames)
        assertTrue(progress.last().completed)
        assertTrue(first.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun preDownloadLeavesParsingUntilTheFirstLoad() {
        server.enqueue(response())
        runTask { loader.preDownload(request()) }
        assertEquals(0, engine.decodeCount.get())
        assertEquals(2, runTask { loader.load(request().copy(cacheOnly = true)) }.frames)
        assertEquals(1, server.requestCount)
        assertEquals(1, engine.decodeCount.get())
    }

    @Test fun closingAdapterCancelsItsRequestAndKeepsSharedEngineUsable() {
        server.enqueue(response().setBodyDelay(400, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val pending = scope.async { loader.load(request()) { started.complete(Unit) } }
        await(scope.async { started.await() })
        loader.close()
        runTask { pending.join() }
        assertTrue(pending.isCancelled)
        server.enqueue(response())
        SvgaImageLoader(RuntimeEnvironment.getApplication(), engine).use { next ->
            assertEquals(2, runTask { next.load(request("/next.svga")) }.frames)
        }
    }

    @Test fun dynamicImagesSurviveGlideClearsAndRecycleWithTheirInstance() {
        val file = png(1024, 64)
        val bindings = svgaBindings { image("avatar", file, size = 64) }
        val first = runTask { bindings.prepare(loader.imageLoader, context, SvgaCachePolicy.ALL) }
        val second = runTask { bindings.prepare(loader.imageLoader, context, SvgaCachePolicy.ALL) }
        val firstBitmap = first.getDynamicImage("avatar")!!
        val secondBitmap = second.getDynamicImage("avatar")!!
        assertNotSame(firstBitmap, secondBitmap)
        assertTrue(maxOf(firstBitmap.width, firstBitmap.height) <= 64)
        assertEquals(Color.RED, firstBitmap.getPixel(0, 0))
        first.clearDynamicObjects()
        assertTrue(firstBitmap.isRecycled)
        assertFalse(secondBitmap.isRecycled)
        assertEquals(Color.RED, secondBitmap.getPixel(0, 0))
        second.clearDynamicObjects()
        assertTrue(secondBitmap.isRecycled)
    }

    @Test fun identicalDynamicImagesReuseGlideMemoryCache() {
        val sources = mutableListOf<DataSource>()
        loader.imageLoader.addDefaultRequestListener(object : RequestListener<Any> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Any>, isFirstResource: Boolean) = false
            override fun onResourceReady(resource: Any, model: Any, target: Target<Any>?, dataSource: DataSource,
                isFirstResource: Boolean): Boolean {
                if (resource is Bitmap) sources.add(dataSource)
                return false
            }
        })
        val file = png(128, 64)
        repeat(2) {
            val dynamic = runTask {
                svgaBindings { image("avatar", file, size = 64) }.prepare(loader.imageLoader, context, SvgaCachePolicy.ALL)
            }
            dynamic.clearDynamicObjects()
        }
        assertEquals(2, sources.size)
        assertEquals(DataSource.MEMORY_CACHE, sources.last())

        val uncached = runTask {
            svgaBindings { image("avatar", file, size = 64) }.prepare(loader.imageLoader, context, SvgaCachePolicy.NONE)
        }
        uncached.clearDynamicObjects()
        assertNotEquals(DataSource.MEMORY_CACHE, sources.last())
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun circlesAndOptionalFailuresKeepFallbackAndBorrowedPixels() {
        val borrowed = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bindings = svgaBindings {
            image("borrowed", borrowed)
            image("avatar", png(128, 64), size = 32, circleCrop = true)
            image("avatar", File(temporary.root, "missing.png"), required = false)
        }
        val dynamic = runTask { bindings.prepare(loader.imageLoader, context, SvgaCachePolicy.NONE) }
        val circle = dynamic.getDynamicImage("avatar")!!
        assertEquals(32, circle.width); assertEquals(32, circle.height)
        assertEquals(0, Color.alpha(circle.getPixel(0, 0)))
        assertEquals(Color.RED, circle.getPixel(16, 16))
        dynamic.clearDynamicObjects()
        assertTrue(circle.isRecycled)
        assertFalse(borrowed.isRecycled)
    }

    @Test fun replacingOwnedPixelsRecyclesOnlyTheOwnedBitmap() {
        val owned = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val borrowed = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val dynamic = SVGADynamicEntity()
        dynamic.setOwnedDynamicImage(owned, "avatar")
        dynamic.setDynamicImage(borrowed, "avatar")
        assertTrue(owned.isRecycled)
        dynamic.clearDynamicObjects()
        assertFalse(borrowed.isRecycled)
    }

    @Test fun viewLoadsStaticFrameReusesMatchingRequestAndReleasesOnDetach() {
        val file = temporary.newFile("gift.svga").apply { writeBytes(data) }
        val ready = CompletableDeferred<Unit>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(activity.get())
            val view = SVGAImageView(activity.get())
            activity.get().setContentView(root)
            root.addView(view, FrameLayout.LayoutParams(192, 192))
            view.layout(0, 0, 192, 192)
            val first = view.loadSvga(file) {
                loader = this@SvgaGlideLoaderTest.loader
                staticImage = true
                onReady = { ready.complete(Unit) }
                onError = { ready.completeExceptionally(it) }
            }
            await(scope.async { ready.await() })
            assertEquals(0, (view.drawable as SVGADrawable).currentFrame)
            val second = view.loadSvga(file) { loader = this@SvgaGlideLoaderTest.loader; staticImage = true }
            assertSame(first, second)
            root.removeView(view)
            assertNull(view.drawable)
            first.resume()
            assertNull(view.drawable)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun viewControlsPreservePlayingFrameWhenBindingsChange() = assertViewControlsSurviveBindingUpdate(true)

    @Test fun viewControlsPreservePausedFrameWhenBindingsChange() = assertViewControlsSurviveBindingUpdate(false)

    private fun assertViewControlsSurviveBindingUpdate(shouldPlay: Boolean) {
        val file = temporary.newFile("gift.svga").apply { writeBytes(data) }
        val ready = CompletableDeferred<Unit>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = SVGAImageView(activity.get())
            activity.get().setContentView(view)
            view.layout(0, 0, 192, 192)
            val handle = view.loadSvga(file) {
                loader = this@SvgaGlideLoaderTest.loader
                useViewControls = true
                autoPlay = !shouldPlay
                bindings = svgaBindings { image("avatar", png(64, 32), size = 32) }
                onReady = { ready.complete(Unit) }
                onError = { ready.completeExceptionally(it) }
            }
            await(scope.async { ready.await() })
            val original = view.drawable as SVGADrawable
            val owned = original.dynamicItem.getDynamicImage("avatar")!!
            // Manual View controls can change playback independently of the handle's autoplay option.
            view.stepToFrame(1, shouldPlay)
            handle.updateBindings(svgaBindings { text("name", "updated") })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (view.drawable === original && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            val replacement = view.drawable as SVGADrawable
            assertNotSame(original, replacement)
            assertEquals(1, replacement.currentFrame)
            assertEquals(shouldPlay, view.isAnimating)
            assertTrue(owned.isRecycled)
            view.clearSvga()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun replacingViewRequestCancelsPendingBindingsAndClearsOwnedPixels() {
        val file = temporary.newFile("gift.svga").apply { writeBytes(data) }
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = SVGAImageView(activity.get())
            activity.get().setContentView(view)
            view.layout(0, 0, 192, 192)
            view.loadSvga(file) {
                loader = this@SvgaGlideLoaderTest.loader; staticImage = true
                bindingsFactory = {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                onError = { started.completeExceptionally(it) }
            }
            await(scope.async { started.await() })
            val image = png(64, 32)
            view.loadSvga(file) {
                loader = this@SvgaGlideLoaderTest.loader; staticImage = true
                bindings = svgaBindings { image("avatar", image, size = 32) }
                onReady = { ready.complete(Unit) }
                onError = { ready.completeExceptionally(it) }
            }
            await(scope.async { cancelled.await(); ready.await() })
            val bitmap = (view.drawable as SVGADrawable).dynamicItem.getDynamicImage("avatar")!!
            view.clearSvga()
            assertTrue(bitmap.isRecycled)
            assertNull(view.drawable)
        } finally { activity.pause().stop().destroy() }
    }
}
