@file:Suppress("DEPRECATION")
package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.SvgaRequest
import com.opensource.svgaplayer.loader.SvgaSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SvgaViewConcurrencyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val asset = SvgaSource.Asset("rose_2.0.0.svga")

    @Test fun imageCompletionRunsOnMainAndCanStartViewControlledPlayback() {
        val complete = CountDownLatch(1)
        val stepped = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val callbackThread = AtomicReference<Thread>()
        lateinit var view: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = SVGAImageView(activity)
                view.callback = object : SVGACallback {
                    override fun onStep(frame: Int, percentage: Double) { stepped.countDown() }
                    override fun onFinished() {}
                    override fun onPause() {}
                    override fun onRepeat() {}
                }
                activity.setContentView(view, FrameLayout.LayoutParams(128, 128))
                view.loadSvga(asset) {
                    useViewControls = true; autoPlay = false
                    onResourceReady = { entity ->
                        view.parserImagesEndCallBack = {
                            callbackThread.set(Thread.currentThread())
                            try { view.startAnimation() } catch (e: Throwable) { failure.set(e) }
                            finally { complete.countDown() }
                        }
                        view.setVideoItem(entity)
                        true
                    }
                    onError = { failure.set(it); complete.countDown() }
                }
            }
            assertTrue("Image completion must arrive", complete.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertSame(Looper.getMainLooper().thread, callbackThread.get())
            assertTrue("Playback started by the callback must advance", stepped.await(10, TimeUnit.SECONDS))
            scenario.onActivity { view.clearSvga() }
        }
    }

    @Test fun replacedDrawableCannotDeliverItsLateImageCompletion() = lateCompletion("replace")
    @Test fun clearedDrawableCannotDeliverItsLateImageCompletion() = lateCompletion("clear")
    @Test fun reattachedViewCannotDeliverThePreviousAttachmentsImageCompletion() = lateCompletion("reattach")

    private fun lateCompletion(transition: String) {
        val resource = runBlocking { SvgaImageLoader.get(context).load(SvgaRequest(asset, 128, 128)) }
        val entered = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val currentComplete = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val previousDecoder = SVGAParser.getBitmapDecoder()
        val previousExecutor = SVGAParser::class.java.getDeclaredField("threadPoolExecutor").apply {
            isAccessible = true
        }.get(null) as ThreadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val pixels = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val directory = File(context.cacheDir, "view-concurrency-${UUID.randomUUID()}").apply { mkdirs() }
        File(directory, "image.png").writeBytes(byteArrayOf(1))
        val legacy = SVGAVideoEntity(JSONObject("""{"movie":{"viewBox":{"width":16,"height":16},"fps":20,"frames":1},"images":{"image":"image.png"},"sprites":[]}"""), directory)
        SVGAParser.setThreadPoolExecutor(executor)
        SVGAParser.setBitmapDecoder(object : SVGAParser.BitmapDecoder by previousDecoder {
            override fun onLoad(imageView: View, path: String, scaleX: Float, scaleY: Float,
                frameWidth: Int, frameHeight: Int, videoWidth: Int, videoHeight: Int): Bitmap {
                entered.countDown()
                check(finishDecode.await(10, TimeUnit.SECONDS))
                return pixels
            }
        })
        lateinit var view: SVGAImageView
        lateinit var root: FrameLayout
        var expectedDrawable: SVGADrawable? = null
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        root = FrameLayout(activity)
                        view = SVGAImageView(activity)
                        root.addView(view, FrameLayout.LayoutParams(128, 128))
                        activity.setContentView(root)
                        view.parserImagesEndCallBack = { callbackCount.incrementAndGet(); currentComplete.countDown() }
                        view.setVideoItem(legacy)
                    }
                    assertTrue("Old decoding must be in flight", entered.await(10, TimeUnit.SECONDS))
                    scenario.onActivity {
                        when (transition) {
                            "replace" -> view.setVideoItem(resource.newVideoEntity())
                            "clear" -> view.clear()
                            "reattach" -> {
                                root.removeView(view)
                                view.setVideoItem(resource.newVideoEntity())
                                view.parserImagesEndCallBack = { callbackCount.incrementAndGet(); currentComplete.countDown() }
                                root.addView(view)
                            }
                        }
                        expectedDrawable = view.drawable as? SVGADrawable
                    }
                } finally { finishDecode.countDown() }
                if (transition != "clear") assertTrue("The replacement must complete", currentComplete.await(10, TimeUnit.SECONDS))
                // The single worker puts every completed decoding task's Main continuation
                // in the queue before this barrier; then drain Main as well.
                executor.execute { drained.countDown() }
                assertTrue(drained.await(10, TimeUnit.SECONDS))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals("Only the current presentation may report completion",
                    if (transition == "clear") 0 else 1, callbackCount.get())
                scenario.onActivity {
                    assertSame(expectedDrawable, view.drawable)
                    view.clear()
                }
            }
        } finally {
            finishDecode.countDown()
            SVGAParser.setBitmapDecoder(previousDecoder)
            SVGAParser.setThreadPoolExecutor(previousExecutor)
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            legacy.clear()
            directory.deleteRecursively()
        }
    }
}
