package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.SvgaSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Uses real Android Paths and Canvases; native races cannot be covered by JVM graphics stubs. */
class SvgaConcurrentDrawingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var host: ActivityScenario<ComponentActivity>

    @Before fun attachHost() {
        // Keep native graphics tests attached to a real window, like View playback.
        host = ActivityScenario.launch(ComponentActivity::class.java)
        host.onActivity { it.setContentView(FrameLayout(it)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After fun closeHost() { if (::host.isInitialized) host.close() }

    private fun fixture(masked: Boolean = true): ByteArray {
        val pixels = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        pixels.eraseColor(Color.RED)
        val image = ByteArrayOutputStream().also {
            pixels.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        pixels.recycle()
        val mask = buildString {
            append("M 0 0 ")
            repeat(128) { append("C 0 0 64 0 64 64 C 64 64 0 64 0 0 ") }
            append("Z")
        }
        val frames = JSONArray(List(8) {
            JSONObject().put("alpha", 1).put("layout", JSONObject().put("width", 64).put("height", 64))
                .put("transform", JSONObject().put("a", 1).put("d", 1).put("tx", it))
                .put("clipPath", if (masked) mask else "")
        })
        val spec = JSONObject().put("movie", JSONObject().put("viewBox",
            JSONObject().put("width", 64).put("height", 64)).put("fps", 30).put("frames", 8))
            .put("images", JSONObject().put("image", "image.png"))
            .put("sprites", JSONArray().put(JSONObject().put("imageKey", "image").put("frames", frames)))
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec.toString().toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("image.png")); zip.write(image); zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun resource(masked: Boolean = true): SvgaResource = runBlocking {
        val directory = File(context.cacheDir, "drawing-${UUID.randomUUID()}")
        try { SvgaResource.decode(fixture(masked).inputStream(), directory) }
        finally { directory.deleteRecursively() }
    }

    private fun drawing(resource: SvgaResource, bindings: SVGADynamicEntity = SVGADynamicEntity()) =
        SVGADrawable(resource.newVideoEntity(), bindings).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            showStaticFrame()
        }

    private fun concurrently(first: () -> Unit, second: () -> Unit) {
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { start.await(); first() } catch (error: Throwable) { failure.set(error) }
        }.apply { name = "SVGA-prepare-regression"; start() }
        try {
            start.countDown()
            second()
        } finally {
            worker.join(20_000)
        }
        assertFalse("Preparation worker must finish", worker.isAlive)
        failure.get()?.let { throw AssertionError("Preparation failed", it) }
    }

    @Test fun maskedDrawableSurvivesConcurrentPreparationAndDrawing() {
        val drawable = drawing(resource())
        val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            concurrently(
                { repeat(2_000) { drawable.prepare(128, 128) } },
                { repeat(2_000) { drawable.draw(Canvas(output)) } },
            )
            output.eraseColor(Color.TRANSPARENT)
            drawable.draw(Canvas(output))
            assertEquals("Clipping must still render the expected pixels", Color.RED, output.getPixel(64, 64))
        } finally { drawable.clear(); output.recycle() }
    }

    @Test fun maskedRendererSurvivesConcurrentPreparationAndDrawing() {
        val renderer = resource().newRenderer()
        val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            concurrently(
                { repeat(2_000) { renderer.prepare(128, 128) } },
                { repeat(2_000) { renderer.draw(Canvas(output), 128, 128, 0, 0L) } },
            )
            output.eraseColor(Color.TRANSPARENT)
            renderer.draw(Canvas(output), 128, 128, 0, 0L)
            assertEquals(Color.RED, output.getPixel(64, 64))
        } finally { renderer.close(); output.recycle() }
    }

    @Test fun clearWaitsForDrawableRenderingAndReleasedDrawablesStayIdle() {
        verifyReleaseWaits(renderer = false)
    }

    @Test fun closeWaitsForRendererRenderingAndReleasedRenderersStayIdle() {
        verifyReleaseWaits(renderer = true)
    }

    @Test fun setVideoItemKeepsTheDrawableHiddenUntilPreparationFinishes() {
        val entered = CountDownLatch(1)
        val finishRender = CountDownLatch(1)
        val stepped = CountDownLatch(1)
        val callbackTimedOut = AtomicReference<Boolean>(false)
        val drawnBeforePreparation = AtomicReference<Boolean>(false)
        val bindings = SVGADynamicEntity().apply {
            setDynamicDrawer({ _, _ ->
                if (Thread.currentThread() !== Looper.getMainLooper().thread) {
                    entered.countDown()
                    if (!finishRender.await(10, TimeUnit.SECONDS)) callbackTimedOut.set(true)
                } else if (finishRender.count > 0) drawnBeforePreparation.set(true)
                true
            }, "image")
        }
        val source = resource(masked = false)
        lateinit var view: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    view = SVGAImageView(activity)
                    activity.setContentView(view, FrameLayout.LayoutParams(128, 128))
                    view.callback = object : SVGACallback {
                        override fun onStep(frame: Int, percentage: Double) { stepped.countDown() }
                        override fun onFinished() {}
                        override fun onPause() {}
                        override fun onRepeat() {}
                    }
                    view.setVideoItem(source.newVideoEntity(), bindings)
                    view.startAnimation()
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    val drawable = view.drawable as SVGADrawable
                    assertTrue("The attached drawable must stay hidden while preparing", drawable.cleared)
                    val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    try { drawable.draw(Canvas(output)); assertEquals(Color.TRANSPARENT, output.getPixel(64, 64)) }
                    finally { output.recycle() }
                }
                assertFalse(drawnBeforePreparation.get())
            } finally { finishRender.countDown() }
            assertTrue("Prepared playback must become visible and advance", stepped.await(10, TimeUnit.SECONDS))
            assertFalse(callbackTimedOut.get())
            scenario.onActivity { assertFalse((view.drawable as SVGADrawable).cleared); view.clear() }
        }
    }

    private fun verifyReleaseWaits(renderer: Boolean) {
        val entered = CountDownLatch(1)
        val finishRender = CountDownLatch(1)
        val releaseStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        val callbackTimedOut = AtomicReference<Boolean>(false)
        val failure = AtomicReference<Throwable>()
        val bindings = SVGADynamicEntity().apply {
            setDynamicDrawer({ _, _ ->
                calls.incrementAndGet()
                entered.countDown()
                if (!finishRender.await(10, TimeUnit.SECONDS)) callbackTimedOut.set(true)
                true
            }, "image")
        }
        val source = resource(masked = false)
        val drawable = if (!renderer) drawing(source, bindings) else null
        val drawing = if (renderer) source.newRenderer(bindings) else null
        fun prepare() { if (drawing != null) drawing.prepare(128, 128) else drawable!!.prepare(128, 128) }
        fun release() { if (drawing != null) drawing.close() else drawable!!.clear() }
        val worker = Thread {
            try { prepare() } catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        val closer = Thread {
            try { releaseStarted.countDown(); release() }
            catch (error: Throwable) { failure.set(error) }
            finally { released.countDown() }
        }
        try {
            assertTrue("The worker must be inside a real render callback", entered.await(10, TimeUnit.SECONDS))
            closer.start()
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Release must not clear resources used by an in-flight render", released.await(150, TimeUnit.MILLISECONDS))
        } finally {
            finishRender.countDown()
            worker.join(10_000)
            if (closer.state != Thread.State.NEW) closer.join(10_000)
            release()
        }
        assertFalse(worker.isAlive); assertFalse(closer.isAlive)
        assertFalse(callbackTimedOut.get())
        failure.get()?.let { throw AssertionError("Render/release failed", it) }
        val count = calls.get()
        val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            prepare()
            if (drawing != null) drawing.draw(Canvas(output), 128, 128, 0, 0L) else drawable!!.draw(Canvas(output))
            assertEquals("Released presentations must not render or rebuild caches", count, calls.get())
            assertEquals(Color.TRANSPARENT, output.getPixel(64, 64))
        } finally { output.recycle() }
    }

    @Test fun setVideoItemAndBothLoadSvgaModesPlayMaskedResources() {
        val file = File(context.cacheDir, "masked-playback-${UUID.randomUUID()}.svga").apply { writeBytes(fixture()) }
        val resource = resource()
        try {
            // Manual resource binding, the default loader, and the loader's legacy View controls.
            for (mode in 0..2) {
                val shown = CountDownLatch(1)
                val failure = AtomicReference<Throwable>()
                lateinit var view: SVGAImageView
                var handle: SvgaViewHandle? = null
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val root = FrameLayout(activity)
                        view = SVGAImageView(activity)
                        root.addView(view, FrameLayout.LayoutParams(128, 128))
                        activity.setContentView(root)
                        if (mode == 0) {
                            view.callback = object : SVGACallback {
                                override fun onStep(frame: Int, percentage: Double) { if (frame > 0) shown.countDown() }
                                override fun onFinished() {}
                                override fun onPause() {}
                                override fun onRepeat() {}
                            }
                            view.setVideoItem(resource.newVideoEntity())
                            view.startAnimation()
                        } else {
                            handle = view.loadSvga(SvgaSource.LocalFile(file)) {
                                useViewControls = mode == 2
                                onReady = { shown.countDown() }
                                onError = { failure.set(it); shown.countDown() }
                            }
                        }
                    }
                    assertTrue("Mode $mode must start playback", shown.await(15, TimeUnit.SECONDS))
                    assertNull(failure.get())
                    // Wait for an actual Choreographer frame, including asynchronous legacy preparation.
                    val advanced = CountDownLatch(1)
                    scenario.onActivity {
                        fun checkFrame() {
                            if ((view.drawable as? SVGADrawable)?.currentFrame?.let { it > 0 } == true) advanced.countDown()
                            else view.postDelayed({ checkFrame() }, 16)
                        }
                        checkFrame()
                    }
                    assertTrue("Mode $mode must advance visible frames", advanced.await(10, TimeUnit.SECONDS))
                    scenario.onActivity {
                        val drawable = view.drawable as SVGADrawable
                        assertFalse(drawable.cleared)
                        if (mode == 1) {
                            handle!!.pause(); handle!!.seekToProgress(0f)
                        } else {
                            view.pauseAnimation(); view.stepToFrame(0, false)
                        }
                        val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                        try { view.draw(Canvas(output)); assertEquals(Color.RED, output.getPixel(64, 64)) }
                        finally { output.recycle() }
                        if (mode == 0) view.clear() else view.clearSvga()
                        assertNull(view.drawable)
                    }
                }
            }
        } finally { file.delete() }
    }
}
