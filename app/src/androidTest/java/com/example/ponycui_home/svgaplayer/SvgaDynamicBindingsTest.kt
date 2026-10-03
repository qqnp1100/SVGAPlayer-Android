package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.SvgaSource
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SvgaDynamicBindingsTest {
    @Test fun staticBindingsFactoryPreparesWithoutStartingPlayback() {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        var factoryCalled = false
        lateinit var view: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = SVGAImageView(activity); activity.setContentView(view)
                view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    useViewControls = true; staticImage = true
                    bindingsFactory = { resource ->
                        assertTrue(resource.width > 0)
                        factoryCalled = true
                        svgaBindings { text("name", "static"); textScroll("name", 15f, 30f) }
                    }
                    onReady = { ready.countDown() }
                    onError = { failure = it; ready.countDown() }
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                assertTrue(factoryCalled)
                assertFalse(view.isAnimating)
                val drawable = view.drawable as SVGADrawable
                assertEquals(0, drawable.currentFrame)
                assertEquals(30f, drawable.dynamicItem.srcollTextSpace, 0f)
                view.clearSvga()
            }
        }
    }

    @Test fun coilImagesAndTextSupportViewPlaybackAndReattachment() {
        val ready = CountDownLatch(1)
        val reattached = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        var loads = 0
        lateinit var view: SVGAImageView
        lateinit var root: FrameLayout
        var firstDynamic: Any? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                root = FrameLayout(activity)
                view = SVGAImageView(activity).apply { loops = 1 }
                root.addView(view, FrameLayout.LayoutParams(192, 192))
                activity.setContentView(root)
                val png = java.io.File(activity.cacheDir, "dynamic-binding-test.png")
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                view.callback = object : SVGACallback {
                    override fun onFinished() { finished.countDown() }
                    override fun onPause() {}
                    override fun onRepeat() {}
                    override fun onStep(frame: Int, percentage: Double) {}
                }
                view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    useViewControls = true; restartOnAttach = true; autoPlay = false
                    bindingsFactory = {
                        svgaBindings {
                            image("replacement", png, size = 16)
                            // An optional failure must retain the previously bound fallback.
                            image("replacement", java.io.File(activity.cacheDir, "missing-binding.png"), required = false)
                            val paint = TextPaint().apply { textSize = 22f; color = Color.WHITE }
                            text("name", "owner", paint)
                            text("layout", StaticLayout.Builder.obtain("hello", 0, 5, paint, 100)
                                .setAlignment(Layout.Alignment.ALIGN_CENTER).build())
                            textScroll("name", 30f, 30f)
                        }
                    }
                    onReady = { if (++loads == 1) ready.countDown() else reattached.countDown() }
                    onError = { failure = it; ready.countDown(); reattached.countDown() }
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                val dynamic = (view.drawable as SVGADrawable).dynamicItem
                firstDynamic = dynamic
                val images = dynamic.javaClass.getDeclaredField("dynamicInImage").apply { isAccessible = true }
                    .get(dynamic) as Map<*, *>
                val outImages = dynamic.javaClass.getDeclaredField("dynamicOutImage").apply { isAccessible = true }
                    .get(dynamic) as Map<*, *>
                val bitmap = (images["replacement"] ?: outImages["replacement"]) as Bitmap
                assertEquals(Color.RED, bitmap.getPixel(8, 8))
                assertEquals(30f, dynamic.srcollTextSpace, 0f)
                view.startAnimation(com.opensource.svgaplayer.utils.SVGARange(0, 3))
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            scenario.onActivity {
                assertEquals(2, (view.drawable as SVGADrawable).currentFrame)
                root.removeView(view); assertNull(view.drawable); root.addView(view)
            }
            assertTrue(reattached.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                assertNotSame(firstDynamic, (view.drawable as SVGADrawable).dynamicItem)
                view.stepToFrame(7, false)
                assertEquals(7, (view.drawable as SVGADrawable).currentFrame)
                view.clearSvga()
            }
        }
    }

    @Test fun replacingARequestCancelsBindingPreparation() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val ready = CountDownLatch(1)
        var staleReady = false
        var failure: Throwable? = null
        lateinit var view: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = SVGAImageView(activity); activity.setContentView(view)
                view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    useViewControls = true
                    bindingsFactory = {
                        started.countDown()
                        try { awaitCancellation() } finally { cancelled.countDown() }
                    }
                    onReady = { staleReady = true }
                    onError = { failure = it; started.countDown() }
                }
            }
            assertTrue(started.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity {
                view.loadSvga(SvgaSource.Asset("rose_2.0.0.svga")) {
                    useViewControls = true; autoPlay = false
                    bindings = svgaBindings { text("name", "new") }
                    onReady = { ready.countDown() }
                    onError = { failure = it; ready.countDown() }
                }
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
            scenario.onActivity { assertFalse(staleReady); assertNotNull(view.drawable); view.clearSvga() }
        }
    }
}
