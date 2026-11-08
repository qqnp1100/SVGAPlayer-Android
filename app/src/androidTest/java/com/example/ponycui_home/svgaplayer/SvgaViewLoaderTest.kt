package com.example.ponycui_home.svgaplayer

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.SvgaSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SvgaViewLoaderTest {
    @Test fun requestCreatedDuringAttachLoadsOnlyOnce() {
        val ready = CountDownLatch(1)
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = object : SVGAImageView(activity) {
                    override fun onAttachedToWindow() {
                        super.onAttachedToWindow()
                        loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                            useViewControls = true; restartOnAttach = true
                            onResourceReady = { callbacks.incrementAndGet(); ready.countDown(); false }
                        }
                    }
                }
                activity.setContentView(view)
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertEquals(1, callbacks.get())
        }
    }

    @Test fun resourceRequestReplacesPendingLoadReattachesAndClears() {
        val ready = CountDownLatch(1)
        val reattached = CountDownLatch(1)
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val stale = java.util.concurrent.atomic.AtomicInteger()
        var failure: Throwable? = null
        lateinit var view: SVGAImageView
        lateinit var root: android.widget.FrameLayout
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                root = android.widget.FrameLayout(activity)
                activity.setContentView(root)
                view = SVGAImageView(activity)
                // A request issued before attach must not leak or bind after replacement.
                view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    useViewControls = true; restartOnAttach = true
                    requestFactory = { w, h ->
                        kotlinx.coroutines.delay(500)
                        com.opensource.svgaplayer.loader.SvgaRequest(SvgaSource.Asset("rose_2.0.0.svga"), w, h)
                    }
                    onResourceReady = { stale.incrementAndGet(); false }
                }
                root.addView(view, android.widget.FrameLayout.LayoutParams(192, 192))
                view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    useViewControls = true; restartOnAttach = true; autoPlay = false
                    onResourceReady = { entity ->
                        assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                        view.setVideoItem(entity); view.stepToFrame(5, false)
                        if (callbacks.incrementAndGet() == 1) ready.countDown() else reattached.countDown()
                        true
                    }
                    onError = { failure = it; ready.countDown(); reattached.countDown() }
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                assertEquals(5, (view.drawable as SVGADrawable).currentFrame)
                root.removeView(view)
                assertNull(view.drawable)
                root.addView(view)
            }
            assertTrue(reattached.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                view.clearSvga(); root.removeView(view); root.addView(view)
            }
            Thread.sleep(650)
            scenario.onActivity { assertNull(view.drawable) }
            assertEquals(2, callbacks.get()); assertEquals(0, stale.get())
        }
    }

    @Test fun preparedResourceSupportsLegacyControlsWithoutSharingPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resource = kotlinx.coroutines.runBlocking {
            SvgaImageLoader.get(context).load(com.opensource.svgaplayer.loader.SvgaRequest(
                SvgaSource.Asset("rose_2.0.0.svga"), 192, 192))
        }
        val finished = CountDownLatch(1)
        lateinit var first: SVGAImageView
        lateinit var second: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val layout = android.widget.LinearLayout(activity)
                first = SVGAImageView(activity); second = SVGAImageView(activity)
                layout.addView(first, android.widget.LinearLayout.LayoutParams(192, 192))
                layout.addView(second, android.widget.LinearLayout.LayoutParams(192, 192))
                activity.setContentView(layout)
                first.setVideoItem(resource.newVideoEntity())
                second.setVideoItem(resource.newVideoEntity())
                first.loops = 1
                first.callback = object : com.opensource.svgaplayer.SVGACallback {
                    override fun onFinished() { finished.countDown() }
                    override fun onPause() {}
                    override fun onRepeat() {}
                    override fun onStep(frame: Int, percentage: Double) {}
                }
                first.startAnimation(com.opensource.svgaplayer.utils.SVGARange(0, 3))
                second.stepToFrame(5, false)
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            scenario.onActivity {
                assertFalse(first.isAnimating)
                assertEquals(2, (first.drawable as SVGADrawable).currentFrame)
                assertEquals(5, (second.drawable as SVGADrawable).currentFrame)
                first.clear()
                assertFalse((second.drawable as SVGADrawable).videoItem.isRecycleImage())
                second.startAnimation()
                assertTrue(second.isAnimating)
                second.pauseAnimation()
                assertFalse(second.isAnimating)
                second.stepToFrame(7, false)
                assertEquals(7, (second.drawable as SVGADrawable).currentFrame)
                second.clear()
                assertNull(second.drawable)
            }
        }
    }

    @Test fun networkProgressArrivesOnMainBeforeReady() {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        val ready = CountDownLatch(1)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
        var last: com.opensource.svgaplayer.loader.SvgaDownloadProgress? = null
        var failure: Throwable? = null
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)))
            val url = server.url("/view-progress.svga").toString()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = SVGAImageView(activity); activity.setContentView(view)
                    view.loadSvga(url) {
                        onDownloadProgress = {
                            assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                            last = it
                        }
                        onReady = { ready.countDown() }
                        onError = { failure = it; ready.countDown() }
                    }
                }
                assertTrue(ready.await(20, TimeUnit.SECONDS)); assertNull(failure)
                assertTrue(last!!.completed); assertEquals(bytes.size.toLong(), last!!.bytesRead)
            }
        } finally { server.shutdown() }
    }

    @Test fun preparedViewPlaysPausesUpdatesAndDetaches() {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        lateinit var view: SVGAImageView
        lateinit var handle: SvgaViewHandle
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = SVGAImageView(activity)
                activity.setContentView(view)
                handle = view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    onReady = { ready.countDown() }
                    onError = { error = it; ready.countDown() }
                }
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS)); assertNull(error)
            Thread.sleep(300)
            var frame = 0
            scenario.onActivity { handle.pause(); frame = (view.drawable as SVGADrawable).currentFrame }
            Thread.sleep(200)
            scenario.onActivity {
                assertEquals(frame, (view.drawable as SVGADrawable).currentFrame)
                handle.seekToProgress(.5f); handle.resume()
                handle.updateBindings(svgaBindings { hidden("unused") })
            }
            Thread.sleep(300)
            scenario.onActivity { it.setContentView(android.view.View(it)) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertNull(view.drawable); handle.resume(); assertNull(view.drawable) }
        }
    }
}
