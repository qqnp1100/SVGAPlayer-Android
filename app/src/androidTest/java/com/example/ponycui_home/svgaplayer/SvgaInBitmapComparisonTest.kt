package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional real-device benchmark. Pass sampleAsset; run each mode in a fresh instrumentation process. */
class SvgaInBitmapComparisonTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    @Suppress("UNCHECKED_CAST")
    private fun images(entity: SVGAVideoEntity) = field(entity, "imageMap") as Map<String, Bitmap>
    private fun session(entity: SVGAVideoEntity) = field(entity, "deferredImages")
    private fun encodedBytes(entity: SVGAVideoEntity): Long {
        val deferred = session(entity) ?: return 0
        return (field(deferred, "sources") as Map<*, *>).values.sumOf { (field(it!!, "bytes") as ByteArray).size.toLong() }
    }
    private fun bitmapBytes(entity: SVGAVideoEntity): Long = images(entity).values.sumOf { it.allocationByteCount.toLong() } +
        (session(entity)?.let { field(it, "allocatedBytes") as Long } ?: 0)
    private fun sample(): File? {
        val name = args.getString("sampleAsset") ?: return null
        return File(context.cacheDir, "in-bitmap-comparison.svga").apply {
            instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }
    }
    private fun publish(name: String, report: JSONObject) {
        val output = File(context.getExternalFilesDir(null), "$name.json")
        output.writeText(report.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n${report.toString(2)}\n") })
    }

    @Test fun playbackMemory() {
        val sample = sample(); assumeTrue("No sampleAsset provided", sample != null)
        val width = args.getString("sampleWidth", "750").toInt()
        val height = args.getString("sampleHeight", "1334").toInt()
        val mode = args.getString("sampleMode", "stream")
        require(mode in listOf("default", "filtered", "stream"))
        val ready = CountDownLatch(1); val finished = CountDownLatch(1)
        var failure: Throwable? = null
        var readyAt = 0L; var finishedAt = 0L
        var peakBitmap = 0L; var peakNative = 0L; var peakJava = 0L; var peakPss = 0L
        var initialPss = 0L; var initialNative = 0L; var initialJava = 0L
        var eagerCount = 0; var deferredCount = 0; var retainedEncoded = 0L
        var drawnFrames = 0; var previousFrame = -1; var previousDrawAt = 0L; var maxDrawGapMs = 0.0
        val memory = Debug.MemoryInfo()
        val runtime = Runtime.getRuntime()
        lateinit var view: SVGAImageView
        val engine = SvgaEngine(context, memoryBytes = 0, weakMemoryCacheEnabled = false)
        val loader = SvgaImageLoader(context, engine)
        var start = 0L
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    view = object : SVGAImageView(activity) {
                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val frame = (drawable as? SVGADrawable)?.currentFrame ?: return
                            if (frame != previousFrame) {
                                val now = SystemClock.elapsedRealtimeNanos()
                                if (previousDrawAt > 0) maxDrawGapMs = maxOf(maxDrawGapMs, (now - previousDrawAt) / 1_000_000.0)
                                previousDrawAt = now; previousFrame = frame; drawnFrames++
                            }
                        }
                    }
                    val root = FrameLayout(activity)
                    activity.setContentView(root); root.addView(view, FrameLayout.LayoutParams(width, height))
                }
                runtime.gc(); System.runFinalization(); instrumentation.waitForIdleSync()
                Debug.getMemoryInfo(memory)
                initialPss = memory.totalPss.toLong() * 1024
                initialNative = Debug.getNativeHeapAllocatedSize()
                initialJava = runtime.totalMemory() - runtime.freeMemory()
                scenario.onActivity {
                    start = SystemClock.elapsedRealtimeNanos()
                    view.loadSvga(SvgaRequest(SvgaSource.LocalFile(sample!!), width, height)) {
                        this.loader = loader; iterations = 1; inBitmap = mode == "stream"
                        skipInvisibleImages = mode != "default"
                        onReady = {
                            val entity = (view.drawable as SVGADrawable).videoItem
                            eagerCount = images(entity).size
                            deferredCount = (session(entity)?.let { field(it, "sources") as Map<*, *> })?.size ?: 0
                            retainedEncoded = encodedBytes(entity)
                            readyAt = SystemClock.elapsedRealtimeNanos(); ready.countDown()
                        }
                        onFinished = { finishedAt = SystemClock.elapsedRealtimeNanos(); finished.countDown() }
                        onError = { failure = it; ready.countDown(); finished.countDown() }
                    }
                }
                while (!finished.await(20, TimeUnit.MILLISECONDS)) {
                    assertTrue("Playback timeout", SystemClock.elapsedRealtimeNanos() - start < 30_000_000_000L)
                    Debug.getMemoryInfo(memory)
                    peakPss = maxOf(peakPss, memory.totalPss.toLong() * 1024)
                    peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                    peakJava = maxOf(peakJava, runtime.totalMemory() - runtime.freeMemory())
                    scenario.onActivity {
                        (view.drawable as? SVGADrawable)?.let { peakBitmap = maxOf(peakBitmap, bitmapBytes(it.videoItem)) }
                    }
                }
                assertNull(failure); assertTrue(readyAt > 0)
                scenario.onActivity {
                    val entity = (view.drawable as SVGADrawable).videoItem
                    val report = JSONObject().put("mode", mode).put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
                        .put("width", width).put("height", height).put("frames", entity.frames).put("fps", entity.FPS)
                        .put("eagerImages", eagerCount).put("deferredImages", deferredCount)
                        .put("retainedEncodedBytes", retainedEncoded).put("peakBitmapBytes", peakBitmap)
                        .put("initialPssBytes", initialPss).put("peakPssBytes", peakPss)
                        .put("initialNativeBytes", initialNative).put("peakNativeBytes", peakNative)
                        .put("initialJavaBytes", initialJava).put("peakJavaBytes", peakJava)
                        .put("loadMillis", (readyAt - start) / 1_000_000.0)
                        .put("playMillis", (finishedAt - readyAt) / 1_000_000.0)
                        .put("drawnFrames", drawnFrames).put("maxDrawGapMillis", maxDrawGapMs)
                    publish("in-bitmap-$mode-${width}x$height", report)
                    view.clearSvga()
                }
            }
        } finally { loader.close(); engine.close(); sample?.delete() }
    }

    @Test fun everyFrameVisualComparison() = runBlocking {
        val sample = sample(); assumeTrue("No sampleAsset provided", sample != null)
        val width = args.getString("sampleWidth", "750").toInt()
        val height = args.getString("sampleHeight", "1334").toInt()
        val engine = SvgaEngine(context, memoryBytes = 0, weakMemoryCacheEnabled = false)
        val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val a = IntArray(width * height); val b = IntArray(width * height)
        val report = JSONArray()
        try {
            val request = SvgaRequest(SvgaSource.LocalFile(sample!!), width, height, SvgaCachePolicy.NONE)
            val eager = engine.acquire(request)
            for (mode in listOf("filtered", "stream")) {
                val resource = engine.acquire(request.copy(skipInvisibleImages = true, inBitmap = mode == "stream"))
                var differentFrames = 0; var differentPixels = 0L; var channelError = 0L; var maxChannelError = 0
                eager.newRenderer().use { reference -> resource.newRenderer().use { streamed ->
                    for (frame in 0 until eager.frames) {
                        expected.eraseColor(0); actual.eraseColor(0)
                        reference.draw(Canvas(expected), width, height, frame, 0)
                        streamed.draw(Canvas(actual), width, height, frame, 0)
                        expected.getPixels(a, 0, width, 0, 0, width, height)
                        actual.getPixels(b, 0, width, 0, 0, width, height)
                        var changed = false
                        for (index in a.indices) {
                            if (a[index] == b[index]) continue
                            changed = true; differentPixels++
                            for (shift in listOf(0, 8, 16, 24)) {
                                val difference = kotlin.math.abs(((a[index] ushr shift) and 255) - ((b[index] ushr shift) and 255))
                                channelError += difference; maxChannelError = maxOf(maxChannelError, difference)
                            }
                        }
                        if (changed) differentFrames++
                    }
                } }
                report.put(JSONObject().put("mode", mode).put("frames", eager.frames)
                    .put("differentFrames", differentFrames).put("differentPixels", differentPixels)
                    .put("meanChannelError", channelError.toDouble() / (width.toLong() * height * eager.frames * 4))
                    .put("maxChannelError", maxChannelError))
            }
            publish("in-bitmap-visual-${width}x$height", JSONObject().put("width", width).put("height", height).put("comparisons", report))
        } finally { engine.close(); expected.recycle(); actual.recycle(); sample?.delete() }
    }
}
