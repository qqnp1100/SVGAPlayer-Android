package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Choreographer
import android.view.View
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.*
import com.opensource.svgaplayer.proto.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SvgaInBitmapTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf("frequent", "first", "a", "b", "c", "twice", "mask", "never")
    private val singleFrames = mapOf("first" to 0, "a" to 1, "b" to 3, "c" to 5)

    private fun fixture(binary: Boolean = false, sizes: Map<String, Pair<Int, Int>> = emptyMap(),
                        visibleFrames: Map<String, Int> = singleFrames): ByteArray {
        val images = keys.mapIndexed { index, key ->
            val size = sizes[key] ?: (64 to 64)
            val pixels = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            pixels.eraseColor(Color.argb(128 + index * 15, index * 30, 200 - index * 20, 100))
            key to ByteArrayOutputStream().also {
                pixels.compress(Bitmap.CompressFormat.PNG, 100, it); pixels.recycle()
            }.toByteArray()
        }.toMap()
        val references = keys.map { key -> key to List(8) { frame -> when (key) {
            "frequent" -> 1f
            "twice" -> if (frame == 2) 1f else 0f
            "mask", "never" -> 0f
            else -> if (visibleFrames[key] == frame) 1f else 0f
        } } }.toMutableList().apply { add("twice" to List(8) { if (it == 2) 1f else 0f }) }
        val out = ByteArrayOutputStream()
        if (binary) {
            val sprites = references.map { (key, alphas) -> SpriteEntity(
                if (key == "mask") "mask.matte" else key,
                alphas.map { FrameEntity(it, Layout(0f, 0f, 64f, 64f),
                    Transform(1f, 0f, 0f, 1f, 0f, 0f), "", emptyList()) }, "") }
            val movie = MovieEntity("2.0", MovieParams(64f, 64f, 4, 8),
                images.mapValues { it.value.toByteString() }, sprites, emptyList())
            DeflaterOutputStream(out).use { it.write(MovieEntity.ADAPTER.encode(movie)) }
        } else {
            val spec = JSONObject().put("movie", JSONObject().put("viewBox",
                JSONObject().put("width", 64).put("height", 64)).put("fps", 4).put("frames", 8))
                .put("images", JSONObject(images.mapValues { "${it.key}.png" }))
                .put("sprites", JSONArray(references.map { (key, alphas) -> JSONObject()
                    .put("imageKey", if (key == "mask") "mask.matte" else key)
                    .put("frames", JSONArray(alphas.map { alpha -> JSONObject().put("alpha", alpha.toDouble())
                        .put("layout", JSONObject().put("width", 64).put("height", 64))
                        .put("transform", JSONObject().put("a", 1).put("d", 1)) })) }))
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec.toString().toByteArray()); zip.closeEntry()
                images.forEach { (key, bytes) ->
                    zip.putNextEntry(ZipEntry("$key.png")); zip.write(bytes); zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    @Suppress("UNCHECKED_CAST")
    private fun eagerImages(entity: SVGAVideoEntity) = field(entity, "imageMap") as Map<String, Bitmap>
    private fun session(entity: SVGAVideoEntity) = field(entity, "deferredImages")
    private fun pixels(entity: SVGAVideoEntity, key: String): Bitmap? = session(entity)?.let {
        it.javaClass.getDeclaredMethod("bitmap", String::class.java).invoke(it, key) as Bitmap?
    }
    private fun decode(bytes: ByteArray, enabled: Boolean, budget: Long = 128L * 1024 * 1024): SvgaResource = runBlocking {
        val dir = File(context.cacheDir, "in-bitmap-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try { SvgaResource.decode(bytes.inputStream(), dir, 64, 64, budget, SvgaDecodeOptions(inBitmap = enabled)) }
        finally { dir.deleteRecursively() }
    }
    private fun drawable(resource: SvgaResource) = SVGADrawable(resource.newVideoEntity()).apply {
        scaleType = ImageView.ScaleType.FIT_XY; showStaticFrame()
    }

    @Test fun defersOnlyOneVisibleReferenceAndSurvivesStagingRemoval() {
        for (binary in listOf(false, true)) {
            val resource = decode(fixture(binary), true)
            val entity = resource.newVideoEntity()
            try {
                assertEquals(setOf("frequent", "twice", "mask"), eagerImages(entity).keys)
                assertNotNull(session(entity))
                assertNull(pixels(entity, "first"))
                assertNull(pixels(entity, "b"))
                assertEquals(64, entity.getImageSizeByKey("b")!!.width)
                assertNull(entity.getImageSizeByKey("never"))
            } finally { entity.clear() }
            resource.newRenderer().use { renderer ->
                val output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                try { renderer.draw(Canvas(output), 64, 64, 5, 0) }
                finally { output.recycle() }
            }
        }
    }

    @Test fun pixelsMatchEagerDecodeAndTheSameAllocationIsReused() {
        for (binary in listOf(false, true)) {
            val bytes = fixture(binary)
            val eager = drawable(decode(bytes, false))
            val deferred = drawable(decode(bytes, true))
            val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            var first: Bitmap? = null
            var peak = 0L
            try {
                for (frame in 0 until 8) {
                    expected.eraseColor(Color.TRANSPARENT); actual.eraseColor(Color.TRANSPARENT)
                    eager.advanceExternalClock(frame, 0); deferred.advanceExternalClock(frame, 0)
                    eager.draw(Canvas(expected)); deferred.draw(Canvas(actual))
                    assertTrue("binary=$binary frame=$frame", expected.sameAs(actual))
                    if (frame == 0) first = pixels(deferred.videoItem, "first")
                    if (frame == 3) assertSame(first, pixels(deferred.videoItem, "b"))
                    peak = maxOf(peak, field(session(deferred.videoItem)!!, "allocatedBytes") as Long)
                }
                val predecoded = eagerImages(deferred.videoItem).values.sumOf { it.allocationByteCount.toLong() }
                val all = eagerImages(eager.videoItem).values.sumOf { it.allocationByteCount.toLong() }
                assertTrue("streamed bitmap peak must be below all decoded images", predecoded + peak < all)
                // Seeking back and replaying must restore pixels after earlier allocations were overwritten.
                for (frame in listOf(5, 0, 3, 1)) {
                    expected.eraseColor(Color.TRANSPARENT); actual.eraseColor(Color.TRANSPARENT)
                    eager.advanceExternalClock(frame, 0); deferred.advanceExternalClock(frame, 0)
                    eager.draw(Canvas(expected)); deferred.draw(Canvas(actual))
                    assertTrue("seek $frame", expected.sameAs(actual))
                }
            } finally { eager.clear(); deferred.clear(); expected.recycle(); actual.recycle() }
        }
    }

    @Test fun mutablePixelsBelongToEachPresentation() {
        val resource = decode(fixture(), true)
        val first = drawable(resource); val second = drawable(resource)
        val output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            first.draw(Canvas(output)); second.draw(Canvas(output))
            val a = pixels(first.videoItem, "first")!!
            val b = pixels(second.videoItem, "first")!!
            assertNotSame(a, b)
            first.advanceExternalClock(3, 0); first.draw(Canvas(output)); first.clear()
            assertFalse(b.isRecycled)
            assertSame(b, pixels(second.videoItem, "first"))
            second.draw(Canvas(output))
        } finally { first.clear(); second.clear(); output.recycle() }
    }

    @Test fun uniqueResolutionOnlyDecodesOnDemandAndDoesNotUseThePool() {
        for (binary in listOf(false, true)) {
            // Equal pixel counts with different width/height are still distinct resolutions.
            val bytes = fixture(binary, mapOf("b" to (32 to 48), "c" to (48 to 32)))
            val eager = drawable(decode(bytes, false))
            val deferred = drawable(decode(bytes, true))
            val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            fun draw(frame: Int) {
                expected.eraseColor(0); actual.eraseColor(0)
                eager.advanceExternalClock(frame, 0); deferred.advanceExternalClock(frame, 0)
                eager.draw(Canvas(expected)); deferred.draw(Canvas(actual))
                assertTrue("binary=$binary frame=$frame", expected.sameAs(actual))
            }
            try {
                assertNull(pixels(deferred.videoItem, "b")); assertNull(pixels(deferred.videoItem, "c"))
                draw(0)
                val reusable = pixels(deferred.videoItem, "first")!!
                assertTrue(reusable.isMutable)
                draw(2)
                val manager = session(deferred.videoItem)!!
                val poolBytes = field(manager, "poolBytes") as Long
                assertEquals(reusable.allocationByteCount.toLong(), poolBytes)
                for ((frame, key) in listOf(3 to "b", 5 to "c")) {
                    draw(frame)
                    val unique = pixels(deferred.videoItem, key)!!
                    assertNotSame(reusable, unique)
                    assertFalse("Unique resolution uses ordinary immutable decoding", unique.isMutable)
                    assertEquals("Unique resolution does not consume a larger pooled allocation", poolBytes,
                        field(manager, "poolBytes") as Long)
                    draw(frame + 1)
                    assertNull(pixels(deferred.videoItem, key))
                    assertTrue(unique.isRecycled)
                    assertEquals("Unique resolution never enters the pool", poolBytes, field(manager, "poolBytes") as Long)
                }
            } finally { eager.clear(); deferred.clear(); expected.recycle(); actual.recycle() }
        }
    }

    @Test fun decodeModeSeparatesBothResourceCaches() = runBlocking {
        val file = File.createTempFile("in-bitmap-cache", ".svga", context.cacheDir).apply { writeBytes(fixture()) }
        try {
            for (memory in listOf(0L, 32L * 1024 * 1024)) {
                SvgaEngine(context, memoryBytes = memory).use { engine ->
                    val request = SvgaRequest(SvgaSource.LocalFile(file), 64, 64)
                    val eager = engine.acquire(request)
                    val deferred = engine.acquire(request.copy(inBitmap = true))
                    assertNotSame(eager, deferred)
                    assertSame(eager, engine.acquire(request))
                    assertSame(deferred, engine.acquire(request.copy(inBitmap = true)))
                    assertEquals(2L, engine.decodeCount.get())
                }
            }
        } finally { file.delete() }
    }

    @Test fun streamingBudgetChecksTheActualWorkingSet() {
        val bytes = fixture()
        val imageBytes = 64L * 64 * 4
        assertTrue(runCatching { decode(bytes, false, imageBytes * 6) }.isFailure)
        val streaming = drawable(decode(bytes, true, imageBytes * 6))
        val output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            for (frame in 0 until 8) { streaming.advanceExternalClock(frame, 0); streaming.draw(Canvas(output)) }
        } finally { streaming.clear(); output.recycle() }
        assertTrue(runCatching { decode(bytes, true, imageBytes * 3) }.isFailure)
    }

    @Test fun viewEnablesModeOnlyForOneIterationAndRebindTracksTheOption() {
        val file = File.createTempFile("in-bitmap-view", ".svga", context.cacheDir).apply { writeBytes(fixture()) }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var view: SVGAImageView
                lateinit var previous: SvgaViewHandle
                scenario.onActivity { activity ->
                    view = SVGAImageView(activity); activity.setContentView(view)
                }
                for ((enabled, iterations, static) in listOf(Triple(false, 1, false), Triple(true, 0, false),
                    Triple(true, 2, false), Triple(true, 1, true), Triple(true, 1, false))) {
                    val ready = CountDownLatch(1)
                    var failure: Throwable? = null
                    scenario.onActivity {
                        previous = view.loadSvga(SvgaSource.LocalFile(file)) {
                            inBitmap = enabled; this.iterations = iterations; staticImage = static; autoPlay = false
                            onReady = { ready.countDown() }; onError = { failure = it; ready.countDown() }
                        }
                    }
                    assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
                    scenario.onActivity {
                        assertEquals(enabled && iterations == 1 && !static,
                            session((view.drawable as SVGADrawable).videoItem) != null)
                    }
                }
                scenario.onActivity {
                    val replacement = view.loadSvga(SvgaSource.LocalFile(file)) {
                        inBitmap = false; iterations = 1; autoPlay = false
                    }
                    assertNotSame(previous, replacement)
                    view.clearSvga(); assertNull(view.drawable)
                }
            }
        } finally { file.delete() }
    }

    @Test fun singlePlaybackFinishesAndClearingDuringDecodeCannotReattach() {
        val file = File.createTempFile("in-bitmap-play", ".svga", context.cacheDir).apply { writeBytes(fixture(true)) }
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        var firstPixels: Bitmap? = null
        var hardwareDrawn = false
        var reused = false
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var view: SVGAImageView
                lateinit var handle: SvgaViewHandle
                scenario.onActivity { activity ->
                    view = object : SVGAImageView(activity) {
                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            hardwareDrawn = hardwareDrawn || canvas.isHardwareAccelerated
                            val presentation = drawable as? SVGADrawable ?: return
                            if (presentation.currentFrame == 0) firstPixels = pixels(presentation.videoItem, "first")
                            if (presentation.currentFrame == 3) reused = pixels(presentation.videoItem, "b") === firstPixels
                        }
                    }
                    activity.setContentView(view)
                    handle = view.loadSvga(SvgaSource.LocalFile(file)) {
                        inBitmap = true; iterations = 1
                        onFinished = { finished.countDown() }
                        onError = { failure = it; finished.countDown() }
                    }
                }
                assertTrue(finished.await(15, TimeUnit.SECONDS)); assertNull(failure)
                assertTrue("Exercise hardware display-list retirement", hardwareDrawn)
                assertNotNull(firstPixels)
                assertTrue("Reuse only after the old hardware frame has committed", reused)
                scenario.onActivity {
                    assertEquals(7, (view.drawable as SVGADrawable).currentFrame)
                    handle.seekToProgress(3f / 8); view.clearSvga()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertNull(view.drawable); handle.resume(); assertNull(view.drawable) }
            }
        } finally { file.delete() }
    }

    @Test fun completionSurvivesPausingDuringLastFrameDecode() {
        for (useViewControls in listOf(false, true)) {
            blockedLastFrameDecode(useViewControls, "paused")
        }
    }

    @Test fun decodedFrameWaitsForAVisibleStartedHost() {
        for (useViewControls in listOf(false, true)) {
            for (inactiveHost in listOf("hidden", "stopped")) {
                blockedLastFrameDecode(useViewControls, inactiveHost)
            }
        }
    }

    private fun blockedLastFrameDecode(useViewControls: Boolean, inactiveHost: String) {
        val file = File.createTempFile("in-bitmap-pending", ".svga", context.cacheDir).apply {
            writeBytes(fixture(visibleFrames = singleFrames + ("c" to 7)))
        }
        val ready = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val completions = AtomicInteger()
        val steps = AtomicInteger()
        val lockHeld = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        var blocker: Thread? = null
        var failure: Throwable? = null
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var view: SVGAImageView
                lateinit var handle: SvgaViewHandle
                lateinit var controller: Any
                lateinit var owner: LifecycleOwner
                lateinit var hostLifecycle: LifecycleRegistry
                val finishPlayback = { completions.incrementAndGet(); finished.countDown() }
                var preparing = true
                scenario.onActivity { activity ->
                    view = SVGAImageView(activity).apply { loops = 1; pauseWhenHide = true }
                    activity.setContentView(view)
                    owner = object : LifecycleOwner {
                        override val lifecycle: Lifecycle get() = hostLifecycle
                    }
                    hostLifecycle = LifecycleRegistry(owner).apply { currentState = Lifecycle.State.RESUMED }
                    view.setViewTreeLifecycleOwner(owner)
                    view.callback = object : SVGACallback {
                        override fun onStep(frame: Int, percentage: Double) {
                            if (preparing) {
                                preparing = false; view.pauseAnimation(); prepared.countDown()
                            } else steps.incrementAndGet()
                        }
                        override fun onFinished() { finishPlayback() }
                        override fun onPause() {}
                        override fun onRepeat() {}
                    }
                    handle = view.loadSvga(SvgaSource.LocalFile(file)) {
                        inBitmap = true; iterations = 1; this.useViewControls = useViewControls; autoPlay = false
                        onReady = { ready.countDown() }
                        this.onFinished = finishPlayback
                        onError = { failure = it; ready.countDown(); finished.countDown() }
                    }
                }
                assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
                if (useViewControls) {
                    scenario.onActivity { view.startAnimation() }
                    assertTrue(prepared.await(10, TimeUnit.SECONDS))
                }
                lateinit var decodeLock: Any
                scenario.onActivity {
                    controller = if (useViewControls) field(view, "modernPlayback")!! else handle
                    val presentation = view.drawable as SVGADrawable
                    assertNull(pixels(presentation.videoItem, "c"))
                    decodeLock = field(session(presentation.videoItem)!!, "decodeLock")!!
                }
                // Hold the real decoder lock so this race does not depend on image size or timing.
                blocker = Thread {
                    synchronized(decodeLock) {
                        lockHeld.countDown()
                        releaseDecode.await(15, TimeUnit.SECONDS)
                    }
                }.apply { start() }
                assertTrue(lockHeld.await(5, TimeUnit.SECONDS))
                lateinit var decoding: Job
                scenario.onActivity {
                    if (useViewControls) view.resumeAnimation() else handle.resume()
                    val playback = field(controller, "clock") as SvgaPlayback
                    playback.pause(); playback.tick(0L, 1)
                    (controller as Choreographer.FrameCallback).doFrame(playback.duration + 1)
                    assertTrue(playback.finished)
                    decoding = field(controller, "frameJob") as Job
                    assertTrue(decoding.isActive)
                    when (inactiveHost) {
                        "paused" -> if (useViewControls) view.pauseAnimation() else handle.pause()
                        "hidden" -> view.visibility = View.INVISIBLE
                        "stopped" -> hostLifecycle.currentState = Lifecycle.State.CREATED
                    }
                }
                releaseDecode.countDown()
                runBlocking { withTimeout(5_000) { decoding.join() } }
                scenario.onActivity {
                    assertNull(failure)
                    assertEquals("The pending frame must not be presented to an inactive host", 0,
                        (view.drawable as SVGADrawable).currentFrame)
                    assertEquals(0, steps.get()); assertEquals(0, completions.get())
                    when (inactiveHost) {
                        "paused" -> if (useViewControls) view.resumeAnimation() else handle.resume()
                        "hidden" -> view.visibility = View.VISIBLE
                        "stopped" -> hostLifecycle.currentState = Lifecycle.State.RESUMED
                    }
                }
                assertTrue("The pending completion must be delivered on recovery", finished.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertNull(failure)
                    assertEquals(1, completions.get())
                    assertEquals(7, (view.drawable as SVGADrawable).currentFrame)
                    // Additional ticks and resumes cannot notify completion twice.
                    if (useViewControls) view.resumeAnimation() else handle.resume()
                    (controller as Choreographer.FrameCallback).doFrame(Long.MAX_VALUE)
                    assertEquals(1, completions.get())
                    view.clearSvga()
                }
            }
        } finally {
            releaseDecode.countDown(); blocker?.join(5_000); file.delete()
        }
    }

    @Test fun viewControlsUseLoopsAndChangingToRepeatPreparesAllImages() {
        val file = File.createTempFile("in-bitmap-controls", ".svga", context.cacheDir).apply { writeBytes(fixture()) }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var view: SVGAImageView
                scenario.onActivity { activity ->
                    view = SVGAImageView(activity).apply { loops = 1 }; activity.setContentView(view)
                }
                val ready = CountDownLatch(1); val stepped = CountDownLatch(1); val resumed = CountDownLatch(1)
                var failure: Throwable? = null
                scenario.onActivity {
                    view.loadSvga(SvgaSource.LocalFile(file)) {
                        inBitmap = true; useViewControls = true; autoPlay = false
                        onReady = { ready.countDown() }; onError = { failure = it; ready.countDown() }
                    }
                }
                assertTrue(ready.await(15, TimeUnit.SECONDS)); assertNull(failure)
                scenario.onActivity {
                    assertNotNull(session((view.drawable as SVGADrawable).videoItem))
                    view.callback = object : SVGACallback {
                        override fun onStep(frame: Int, percentage: Double) { stepped.countDown() }
                        override fun onFinished() {}
                        override fun onPause() {}
                        override fun onRepeat() {}
                    }
                    view.startAnimation()
                }
                assertTrue(stepped.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    view.pauseAnimation()
                    assertNull(pixels((view.drawable as SVGADrawable).videoItem, "c"))
                    view.loops = 2
                    view.callback = object : SVGACallback {
                        override fun onStep(frame: Int, percentage: Double) { resumed.countDown() }
                        override fun onFinished() {}
                        override fun onPause() {}
                        override fun onRepeat() {}
                    }
                    view.resumeAnimation()
                }
                assertTrue(resumed.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    view.pauseAnimation()
                    val entity = (view.drawable as SVGADrawable).videoItem
                    singleFrames.keys.forEach { assertNotNull(pixels(entity, it)) }
                    view.clearSvga()
                }
            }
        } finally { file.delete() }
    }
}
