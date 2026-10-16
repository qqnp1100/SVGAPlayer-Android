package com.example.ponycui_home.svgaplayer

import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.SvgaImageLoader
import com.opensource.svgaplayer.coil3.clearSvga
import com.opensource.svgaplayer.coil3.loadSvga
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import com.opensource.svgaplayer.loader.SvgaSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in: one file/mode per fresh instrumentation process; two consecutive real View loads. */
class SvgaDeviceBenchmarkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtime = Runtime.getRuntime()
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun javaBytes() = runtime.totalMemory() - runtime.freeMemory()
    private fun pssBytes(): Long = File("/proc/self/smaps_rollup").useLines { lines ->
        lines.first { it.startsWith("Pss:") }.substringAfter(':').trim().substringBefore(' ').toLong() * 1024
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private class Peaks {
        var pss = 0L; var java = 0L; var native = 0L; var heap = 0L
        var samples = 0; var pssSamples = 0
        @Synchronized fun add(j: Long, n: Long, p: Long?) {
            java = maxOf(java, j); native = maxOf(native, n); heap = maxOf(heap, j + n); samples++
            if (p != null) { pss = maxOf(pss, p); pssSamples++ }
        }
        fun json() = JSONObject().put("pssBytes", pss).put("javaBytes", java)
            .put("nativeBytes", native).put("heapBytes", heap)
            .put("heapSamples", samples).put("pssSamples", pssSamples)
    }

    @Test fun twoConsecutiveLoads() {
        val args = InstrumentationRegistry.getArguments()
        val samplePath = args.getString("benchmarkFile")
        assumeTrue("Optional device benchmark: provide benchmarkFile", samplePath != null)
        val sample = File(samplePath!!)
        assertTrue("Missing SVGA: $sample", sample.isFile)
        val enabled = args.getString("benchmarkInBitmap", "false").toBoolean()
        val id = args.getString("benchmarkId", "sample")
        val width = args.getString("benchmarkWidth", "750").toInt()
        val height = args.getString("benchmarkHeight", "1334").toInt()
        val rows = JSONArray()
        val report = JSONObject().put("id", id).put("inBitmap", enabled)
            .put("samplerVersion", 3).put("pssMethod", "/proc/self/smaps_rollup Pss (excludes GPU memtrack)")
            .put("fileBytes", sample.length()).put("device", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT).put("android", Build.VERSION.RELEASE)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("pid", Process.myPid())
            .put("viewportWidth", width).put("viewportHeight", height)
            .put("bitmapConfig", "ARGB_8888").put("skipInvisibleImages", false)
            .put("strongCacheBytes", 32L * 1024 * 1024).put("weakCache", true)
            .put("rows", rows)
        val engine = SvgaEngine(context)
        val loader = SvgaImageLoader(context, engine)
        var previousFrame = -1; var draws = 0; var maxGap = 0L
        var firstDraw = 0L; var lastDraw = 0L
        var peakBitmapBytes = 0L
        lateinit var view: SVGAImageView
        val output = File(context.getExternalFilesDir(null), "benchmark-$id-$enabled.json")
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    view = object : SVGAImageView(activity) {
                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val presentation = drawable as? SVGADrawable ?: return
                            peakBitmapBytes = maxOf(peakBitmapBytes, presentation.videoItem.imageMapSize().toLong())
                            val frame = presentation.currentFrame
                            if (frame != previousFrame) {
                                val time = now()
                                if (lastDraw > 0) maxGap = maxOf(maxGap, time - lastDraw)
                                if (firstDraw == 0L) firstDraw = time
                                lastDraw = time; previousFrame = frame; draws++
                            }
                        }
                    }
                    val root = FrameLayout(activity)
                    activity.setContentView(root)
                    root.addView(view, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
                runtime.gc(); System.runFinalization(); SystemClock.sleep(200)
                for (pass in 1..2) {
                    val finished = CountDownLatch(1)
                    var error: Throwable? = null
                    var started = 0L; var ready = 0L; var ended = 0L
                    var cpuStart = 0L; var cpuReady = 0L; var cpuEnd = 0L
                    var frames = 0; var fps = 0; var sourceWidth = 0; var sourceHeight = 0
                    var eagerImages = 0; var deferredImages = 0
                    val phase = AtomicInteger(0)
                    val running = AtomicBoolean(true)
                    val loadPeaks = Peaks(); val playPeaks = Peaks()
                    val baselinePss = pssBytes()
                    val baselineJava = javaBytes(); val baselineNative = Debug.getNativeHeapAllocatedSize()
                    loadPeaks.add(baselineJava, baselineNative, baselinePss)
                    val decodedBefore = engine.decodeCount.get()
                    val strongBefore = engine.memoryHits.get(); val weakBefore = engine.weakMemoryHits.get()
                    var samplerCpu = 0L
                    val sampler = Thread({
                        val cpu = Debug.threadCpuTimeNanos()
                        var nextPss = 0L
                        while (running.get()) {
                            val p = phase.get()
                            if (p in 1..2) {
                                val time = now()
                                val pss = if (time >= nextPss) {
                                    pssBytes().also { nextPss = now() + 500_000_000L }
                                } else null
                                (if (p == 1) loadPeaks else playPeaks)
                                    .add(javaBytes(), Debug.getNativeHeapAllocatedSize(), pss)
                            }
                            SystemClock.sleep(200)
                        }
                        samplerCpu = Debug.threadCpuTimeNanos() - cpu
                    }, "svga-benchmark-sampler").apply { start() }
                    try {
                        scenario.onActivity {
                            previousFrame = -1; draws = 0; maxGap = 0; firstDraw = 0; lastDraw = 0
                            peakBitmapBytes = 0L
                            cpuStart = Process.getElapsedCpuTime(); started = now(); phase.set(1)
                            view.loadSvga(SvgaRequest(SvgaSource.LocalFile(sample), width, height)) {
                                this.loader = loader
                                reuseOnRebind = false
                                iterations = 1; inBitmap = enabled; skipInvisibleImages = false
                                bitmapConfig = android.graphics.Bitmap.Config.ARGB_8888
                                onReady = {
                                    ready = now(); cpuReady = Process.getElapsedCpuTime(); phase.set(2)
                                    val video = (view.drawable as SVGADrawable).videoItem
                                    peakBitmapBytes = maxOf(peakBitmapBytes, video.imageMapSize().toLong())
                                    frames = video.frames; fps = video.FPS
                                    sourceWidth = video.videoSize.width.toInt(); sourceHeight = video.videoSize.height.toInt()
                                    eagerImages = (field(video, "imageMap") as Map<*, *>).size
                                    deferredImages = field(video, "deferredImages")?.let {
                                        (field(it, "sources") as Map<*, *>).size
                                    } ?: 0
                                    loadPeaks.add(javaBytes(), Debug.getNativeHeapAllocatedSize(), null)
                                }
                                onFinished = {
                                    ended = now(); cpuEnd = Process.getElapsedCpuTime(); phase.set(0)
                                    playPeaks.add(javaBytes(), Debug.getNativeHeapAllocatedSize(), null)
                                    finished.countDown()
                                }
                                onError = { error = it; phase.set(0); finished.countDown() }
                            }
                        }
                        assertTrue("Playback timeout: $id mode=$enabled pass=$pass", finished.await(180, TimeUnit.SECONDS))
                    } finally { running.set(false); sampler.join() }
                    assertNull("SVGA failed: $error", error)
                    assertTrue("onReady missing", ready > started)
                    assertTrue("onFinished missing", ended > ready)
                    instrumentation.waitForIdleSync()
                    val loadMs = (ready - started) / 1e6; val playMs = (ended - ready) / 1e6
                    val row = JSONObject().put("pass", pass).put("frames", frames).put("fps", fps)
                        .put("eagerImages", eagerImages).put("deferredImages", deferredImages)
                        .put("sourceWidth", sourceWidth).put("sourceHeight", sourceHeight)
                        .put("nominalPlayMillis", frames * 1000.0 / fps)
                        .put("loadMillis", loadMs).put("actualPlayMillis", playMs)
                        .put("totalMillis", (ended - started) / 1e6)
                        .put("loadCpuMillis", cpuReady - cpuStart).put("playCpuMillis", cpuEnd - cpuReady)
                        .put("loadCpuPercent", (cpuReady - cpuStart) * 100.0 / loadMs)
                        .put("playCpuPercent", (cpuEnd - cpuReady) * 100.0 / playMs)
                        .put("samplerCpuMillis", samplerCpu / 1e6)
                        .put("baselinePssBytes", baselinePss).put("baselineJavaBytes", baselineJava)
                        .put("baselineNativeBytes", baselineNative).put("loadPeak", loadPeaks.json())
                        .put("playPeak", playPeaks.json()).put("drawnDistinctFrames", draws)
                        .put("peakBitmapBytes", peakBitmapBytes)
                        .put("maxDrawGapMillis", maxGap / 1e6)
                        .put("firstDrawToLastDrawMillis", (lastDraw - firstDraw) / 1e6)
                        .put("decodeCount", engine.decodeCount.get() - decodedBefore)
                        .put("strongCacheHits", engine.memoryHits.get() - strongBefore)
                        .put("weakCacheHits", engine.weakMemoryHits.get() - weakBefore)
                    assertTrue("No animation frames drawn", draws > 1)
                    rows.put(row)
                    output.writeText(report.toString(2))
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBENCHMARK $id inBitmap=$enabled pass=$pass $row\n") })
                    // Keep the completed presentation and engine alive until loadSvga replaces it.
                    // No explicit GC or resource pinning is performed between the two loads.
                }
                scenario.onActivity { view.clearSvga() }
            }
        } finally { loader.close(); engine.close() }
    }
}
