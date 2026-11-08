# Dynamic Text Scrolling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render overflowing dynamic text as a seamless fixed-60-FPS carousel, with time-based speed and correct static, pause, audio, and drawable lifecycle behavior.

**Architecture:** Pure Kotlin timing and ticker classes provide deterministic behavior under JVM tests. `SVGACanvasDrawer` consumes the timing model and renders one repeated-text rectangle through cached shaders, while `SVGADrawable` schedules invalidations and `SVGAImageView` enables or suspends them according to playback mode.

**Tech Stack:** Kotlin 2.2, Android Canvas/Drawable APIs (minSdk 21), Gradle 8.14.2, Android Gradle Plugin 8.12.0, JUnit 4.13.2.

## Global Constraints

- Refresh target is fixed at 60 FPS, not the display refresh rate.
- Preserve the public API and the visual meaning of `setDynamicTextScrollSpeed` and `srcollTextSpace`.
- Static text refreshes must not advance SVGA frames, replay audio, or invoke animation callbacks.
- Manual `pauseAnimation()` and `stopAnimation(false)` pause text at its current offset; `startAnimation()` resumes without counting paused time.
- Do not allocate bitmaps, shaders, matrices, or layers on steady-state scrolling frames.
- Preserve minSdk 21 compatibility.

---

## File Structure

- Create `library/src/main/java/com/opensource/svgaplayer/drawer/TextScrollTimeline.kt`: per-key offset and monotonic-time calculations.
- Create `library/src/main/java/com/opensource/svgaplayer/TextScrollTicker.kt`: fixed-60-FPS scheduling independent of Android animation frames.
- Create `library/src/main/java/com/opensource/svgaplayer/drawer/FrameChangeGate.kt`: suppress repeated frame-side effects during text-only redraws.
- Modify `library/src/main/java/com/opensource/svgaplayer/drawer/SVGACanvasDrawer.kt`: active-scroll detection, shader carousel rendering, timing, and audio gating.
- Modify `library/src/main/java/com/opensource/svgaplayer/SVGADrawable.kt`: own ticker and bridge drawer activity to invalidation scheduling.
- Modify `library/src/main/java/com/opensource/svgaplayer/SVGAImageView.kt`: enable, pause, replace, attach, and detach lifecycle control.
- Modify `library/build.gradle`: add local JVM test dependency.
- Create focused tests under `library/src/test/java/com/opensource/svgaplayer/` and `library/src/test/java/com/opensource/svgaplayer/drawer/`.

---

### Task 1: Time-Based Offset and Scroll Eligibility

**Files:**
- Modify: `library/build.gradle`
- Create: `library/src/main/java/com/opensource/svgaplayer/drawer/TextScrollTimeline.kt`
- Test: `library/src/test/java/com/opensource/svgaplayer/drawer/TextScrollTimelineTest.kt`

**Interfaces:**
- Produces: `TextScrollTimeline.offsetFor(key: String, cycleWidth: Float, pixelsPerSecond: Float, frameTimeNanos: Long): Float`
- Produces: `TextScrollTimeline.pause()` and `TextScrollTimeline.clear()`
- Produces: `TextScrollTimeline.isActive(textWidth: Int, viewportWidth: Int, speed: Float): Boolean`

- [ ] **Step 1: Add JUnit and write failing timing tests**

Add this dependency to `library/build.gradle`:

```groovy
testImplementation 'junit:junit:4.13.2'
```

Create `TextScrollTimelineTest.kt`:

```kotlin
package com.opensource.svgaplayer.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextScrollTimelineTest {
    @Test
    fun `distance depends on elapsed time instead of draw count`() {
        fun offsetAfterOneSecond(samples: Int): Float {
            val timeline = TextScrollTimeline()
            for (sample in 0..samples) {
                timeline.offsetFor(
                    key = "title",
                    cycleWidth = 1_000f,
                    pixelsPerSecond = 90f,
                    frameTimeNanos = sample * 1_000_000_000L / samples
                )
            }
            return timeline.offsetFor("title", 1_000f, 90f, 1_000_000_000L)
        }

        assertEquals(90f, offsetAfterOneSecond(30), 0.001f)
        assertEquals(90f, offsetAfterOneSecond(60), 0.001f)
        assertEquals(90f, offsetAfterOneSecond(120), 0.001f)
    }

    @Test
    fun `offset wraps at cycle width`() {
        val timeline = TextScrollTimeline()
        timeline.offsetFor("title", 100f, 1_200f, 0L)

        assertEquals(20f, timeline.offsetFor("title", 100f, 1_200f, 100_000_000L), 0.001f)
    }

    @Test
    fun `pause keeps offset and discards paused time`() {
        val timeline = TextScrollTimeline()
        timeline.offsetFor("title", 1_000f, 60f, 0L)
        val beforePause = timeline.offsetFor("title", 1_000f, 60f, 50_000_000L)

        timeline.pause()

        assertEquals(beforePause, timeline.offsetFor("title", 1_000f, 60f, 5_000_000_000L), 0.001f)
        assertEquals(
            beforePause + 3f,
            timeline.offsetFor("title", 1_000f, 60f, 5_050_000_000L),
            0.001f
        )
    }

    @Test
    fun `long frame delay is capped at one hundred milliseconds`() {
        val timeline = TextScrollTimeline()
        timeline.offsetFor("title", 1_000f, 100f, 0L)

        assertEquals(10f, timeline.offsetFor("title", 1_000f, 100f, 2_000_000_000L), 0.001f)
    }

    @Test
    fun `invalid values preserve offset and do not activate scrolling`() {
        val timeline = TextScrollTimeline()
        timeline.offsetFor("title", 100f, 50f, 0L)
        val initial = timeline.offsetFor("title", 100f, 50f, 50_000_000L)

        assertEquals(initial, timeline.offsetFor("title", 0f, 50f, 60_000_000L), 0.001f)
        assertEquals(initial, timeline.offsetFor("title", 100f, Float.NaN, 70_000_000L), 0.001f)
        assertFalse(TextScrollTimeline.isActive(200, 100, Float.NaN))
        assertFalse(TextScrollTimeline.isActive(100, 100, 10f))
        assertFalse(TextScrollTimeline.isActive(200, 100, 0f))
        assertTrue(TextScrollTimeline.isActive(200, 100, 10f))
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
rtk ./gradlew :library:testDebugUnitTest --tests com.opensource.svgaplayer.drawer.TextScrollTimelineTest
```

Expected: compilation fails because `TextScrollTimeline` does not exist.

- [ ] **Step 3: Implement the minimal timeline**

Create `TextScrollTimeline.kt`:

```kotlin
package com.opensource.svgaplayer.drawer

internal class TextScrollTimeline(
    private val maxFrameDeltaNanos: Long = 100_000_000L,
) {
    private data class State(
        var offset: Float = 0f,
        var lastFrameTimeNanos: Long? = null,
    )

    private val states = HashMap<String, State>()

    fun offsetFor(
        key: String,
        cycleWidth: Float,
        pixelsPerSecond: Float,
        frameTimeNanos: Long,
    ): Float {
        val state = states.getOrPut(key) { State() }
        val lastFrameTimeNanos = state.lastFrameTimeNanos
        state.lastFrameTimeNanos = frameTimeNanos
        if (!cycleWidth.isFinite() || cycleWidth <= 0f ||
            !pixelsPerSecond.isFinite() || pixelsPerSecond <= 0f ||
            lastFrameTimeNanos == null
        ) {
            return state.offset
        }
        val elapsedNanos = (frameTimeNanos - lastFrameTimeNanos)
            .coerceIn(0L, maxFrameDeltaNanos)
        val distance = pixelsPerSecond * elapsedNanos.toFloat() / 1_000_000_000f
        state.offset = (state.offset + distance) % cycleWidth
        if (state.offset < 0f) {
            state.offset += cycleWidth
        }
        return state.offset
    }

    fun pause() {
        states.values.forEach { it.lastFrameTimeNanos = null }
    }

    fun clear() {
        states.clear()
    }

    companion object {
        fun isActive(textWidth: Int, viewportWidth: Int, speed: Float): Boolean {
            return textWidth > viewportWidth && viewportWidth > 0 && speed.isFinite() && speed > 0f
        }
    }
}
```

- [ ] **Step 4: Run the focused and module tests and verify GREEN**

Run:

```bash
rtk ./gradlew :library:testDebugUnitTest --tests com.opensource.svgaplayer.drawer.TextScrollTimelineTest
rtk ./gradlew :library:testDebugUnitTest
```

Expected: all tests pass with zero failures.

- [ ] **Step 5: Commit Task 1**

```bash
rtk git add library/build.gradle library/src/main/java/com/opensource/svgaplayer/drawer/TextScrollTimeline.kt library/src/test/java/com/opensource/svgaplayer/drawer/TextScrollTimelineTest.kt
rtk git commit -m "test: define time based text scrolling"
```

---

### Task 2: Fixed 60 FPS Refresh Ticker

**Files:**
- Create: `library/src/main/java/com/opensource/svgaplayer/TextScrollTicker.kt`
- Test: `library/src/test/java/com/opensource/svgaplayer/TextScrollTickerTest.kt`

**Interfaces:**
- Consumes: monotonic uptime milliseconds supplied by the Android drawable at integration time.
- Produces: `setEnabled(Boolean)`, `setHostActive(Boolean)`, `onDraw(Boolean)`, and `stop()`.
- Produces: absolute 16/17 millisecond alternating deadlines averaging 60 FPS.

- [ ] **Step 1: Write failing scheduler tests**

Create `TextScrollTickerTest.kt`:

```kotlin
package com.opensource.svgaplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextScrollTickerTest {
    private class Harness {
        var now = 0L
        var invalidations = 0
        var scheduled: Runnable? = null
        val deadlines = mutableListOf<Long>()
        val ticker = TextScrollTicker(
            nowUptimeMillis = { now },
            schedule = { runnable, deadline ->
                scheduled = runnable
                deadlines += deadline
            },
            unschedule = { runnable -> if (scheduled === runnable) scheduled = null },
            invalidate = { invalidations++ },
        )

        fun runNext() {
            val runnable = requireNotNull(scheduled)
            now = deadlines.last()
            scheduled = null
            runnable.run()
        }
    }

    @Test
    fun `active text schedules an average of sixty frames per second`() {
        val harness = Harness()
        harness.ticker.setEnabled(true)
        harness.ticker.onDraw(true)

        assertEquals(listOf(17L), harness.deadlines)
        harness.runNext()
        harness.runNext()

        assertEquals(listOf(17L, 34L, 50L), harness.deadlines)
        assertTrue(harness.invalidations >= 3)
    }

    @Test
    fun `inactive text does not schedule and pause cancels pending callback`() {
        val harness = Harness()
        harness.ticker.setEnabled(true)
        harness.ticker.onDraw(false)
        assertNull(harness.scheduled)

        harness.ticker.onDraw(true)
        harness.ticker.setEnabled(false)
        assertNull(harness.scheduled)
    }

    @Test
    fun `detach suspends and attach waits for a fresh draw`() {
        val harness = Harness()
        harness.ticker.setEnabled(true)
        harness.ticker.onDraw(true)

        harness.ticker.setHostActive(false)
        assertNull(harness.scheduled)

        harness.ticker.setHostActive(true)
        assertNull(harness.scheduled)
        harness.ticker.onDraw(true)
        assertEquals(2, harness.deadlines.size)
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
rtk ./gradlew :library:testDebugUnitTest --tests com.opensource.svgaplayer.TextScrollTickerTest
```

Expected: compilation fails because `TextScrollTicker` does not exist.

- [ ] **Step 3: Implement the minimal ticker**

Create `TextScrollTicker.kt`:

```kotlin
package com.opensource.svgaplayer

import kotlin.math.ceil
import kotlin.math.floor

internal class TextScrollTicker(
    private val nowUptimeMillis: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val invalidate: () -> Unit,
) {
    private var enabled = false
    private var hostActive = true
    private var hasScrollableText = false
    private var scheduled = false
    private var nextDeadlineMillis = Double.NaN
    private val tick = Runnable {
        scheduled = false
        if (!canRun()) return@Runnable
        invalidate()
        scheduleNext()
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        hasScrollableText = false
        cancel()
        if (enabled && hostActive) invalidate()
    }

    fun setHostActive(active: Boolean) {
        if (hostActive == active) return
        hostActive = active
        hasScrollableText = false
        cancel()
        if (enabled && active) invalidate()
    }

    fun onDraw(hasScrollableText: Boolean) {
        this.hasScrollableText = hasScrollableText
        if (canRun()) scheduleNext() else cancel()
    }

    fun stop() {
        enabled = false
        hasScrollableText = false
        cancel()
    }

    private fun canRun(): Boolean = enabled && hostActive && hasScrollableText

    private fun scheduleNext() {
        if (scheduled) return
        val now = nowUptimeMillis().toDouble()
        if (!nextDeadlineMillis.isFinite()) nextDeadlineMillis = now
        if (nextDeadlineMillis <= now) {
            val missedFrames = floor((now - nextDeadlineMillis) / FRAME_INTERVAL_MILLIS) + 1.0
            nextDeadlineMillis += missedFrames * FRAME_INTERVAL_MILLIS
        }
        scheduled = true
        schedule(tick, ceil(nextDeadlineMillis).toLong())
    }

    private fun cancel() {
        if (scheduled) unschedule(tick)
        scheduled = false
        nextDeadlineMillis = Double.NaN
    }

    companion object {
        private const val FRAME_INTERVAL_MILLIS = 1000.0 / 60.0
    }
}
```

- [ ] **Step 4: Run focused and module tests and verify GREEN**

```bash
rtk ./gradlew :library:testDebugUnitTest --tests com.opensource.svgaplayer.TextScrollTickerTest
rtk ./gradlew :library:testDebugUnitTest
```

Expected: all tests pass with deadlines `17, 34, 50`.

- [ ] **Step 5: Commit Task 2**

```bash
rtk git add library/src/main/java/com/opensource/svgaplayer/TextScrollTicker.kt library/src/test/java/com/opensource/svgaplayer/TextScrollTickerTest.kt
rtk git commit -m "feat: add fixed rate text scroll ticker"
```

---

### Task 3: Single-Pass Carousel Rendering and Audio Isolation

**Files:**
- Create: `library/src/main/java/com/opensource/svgaplayer/drawer/FrameChangeGate.kt`
- Test: `library/src/test/java/com/opensource/svgaplayer/drawer/FrameChangeGateTest.kt`
- Modify: `library/src/main/java/com/opensource/svgaplayer/drawer/SVGACanvasDrawer.kt:22-34,43-54,290-310,539-865,1071-1095`

**Interfaces:**
- Consumes: `TextScrollTimeline` from Task 1.
- Produces: `SVGACanvasDrawer.hasActiveScrollingText: Boolean`.
- Produces: `SVGACanvasDrawer.pauseTextScrolling()`.
- Preserves: transforms, clipping, alpha, edge fade, masks, and the configured gap.

- [ ] **Step 1: Write the failing frame-side-effect regression test**

Create `FrameChangeGateTest.kt`:

```kotlin
package com.opensource.svgaplayer.drawer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeGateTest {
    @Test
    fun `same frame is processed once while looped frame is processed again`() {
        val gate = FrameChangeGate()

        assertTrue(gate.shouldProcess(0))
        assertFalse(gate.shouldProcess(0))
        assertTrue(gate.shouldProcess(1))
        assertTrue(gate.shouldProcess(0))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

```bash
rtk ./gradlew :library:testDebugUnitTest --tests com.opensource.svgaplayer.drawer.FrameChangeGateTest
```

Expected: compilation fails because `FrameChangeGate` does not exist.

- [ ] **Step 3: Implement the frame gate**

Create `FrameChangeGate.kt`:

```kotlin
package com.opensource.svgaplayer.drawer

internal class FrameChangeGate {
    private var lastFrame: Int? = null

    fun shouldProcess(frame: Int): Boolean {
        if (lastFrame == frame) return false
        lastFrame = frame
        return true
    }

    fun clear() {
        lastFrame = null
    }
}
```

- [ ] **Step 4: Replace draw-count movement and bitmap looping in `SVGACanvasDrawer`**

Add state near the existing caches:

```kotlin
private data class ScrollingTextShader(
    val bitmap: Bitmap,
    val viewportWidth: Int,
    val bitmapShader: BitmapShader,
    val composedShader: ComposeShader,
)

private val scrollTimeline = TextScrollTimeline()
private val frameChangeGate = FrameChangeGate()
private val scrollingTextShaders = HashMap<String, ScrollingTextShader>()
private var frameTimeNanos = 0L

var hasActiveScrollingText = false
    private set
```

At the start of `drawFrame`, reset activity, capture one timestamp, and gate audio:

```kotlin
hasActiveScrollingText = false
frameTimeNanos = System.nanoTime()
super.drawFrame(canvas, frameIndex, scaleType)
if (frameChangeGate.shouldProcess(frameIndex)) {
    playAudio(frameIndex)
}
```

Replace the scrolling branch in `drawTextOnBitmap` with:

```kotlin
if (TextScrollTimeline.isActive(textBitmap.width, drawingBitmapWidth, scrollSpeed)) {
    hasActiveScrollingText = true
    drawScrollingTextBitmap(
        canvas,
        imageKey,
        textBitmap,
        drawingBitmapWidth,
        drawingBitmapHeight,
        frameMatrix,
        paint,
        scrollSpeed,
    )
} else {
    canvas.drawBitmap(textBitmap, 0f, 0f, paint)
}
```

Replace `drawScrollingTextBitmap`, `nextScrollTextOffset`, and `scrollTextGradientBitmap` with:

```kotlin
private fun drawScrollingTextBitmap(
    canvas: Canvas,
    imageKey: String,
    textBitmap: Bitmap,
    drawingBitmapWidth: Int,
    drawingBitmapHeight: Int,
    frameMatrix: Matrix,
    paint: Paint,
    configuredSpeed: Float,
) {
    frameMatrix.getValues(matrixValues)
    val pixelsPerSecond = configuredSpeed *
        videoItem.videoSize.width.toFloat() *
        mappedXScale(matrixValues) /
        canvas.width.toFloat()
    val offset = scrollTimeline.offsetFor(
        imageKey,
        textBitmap.width.toFloat(),
        pixelsPerSecond,
        frameTimeNanos,
    )
    val shader = scrollingTextShader(imageKey, textBitmap, drawingBitmapWidth)
    val shaderMatrix = sharedValues.sharedMatrix3()
    shaderMatrix.setTranslate(-offset, 0f)
    shader.bitmapShader.setLocalMatrix(shaderMatrix)
    paint.shader = shader.composedShader
    canvas.drawRect(
        0f,
        0f,
        drawingBitmapWidth.toFloat(),
        drawingBitmapHeight.toFloat(),
        paint,
    )
    paint.shader = null
}

private fun scrollingTextShader(
    imageKey: String,
    textBitmap: Bitmap,
    drawingBitmapWidth: Int,
): ScrollingTextShader {
    scrollingTextShaders[imageKey]?.let {
        if (it.bitmap === textBitmap && it.viewportWidth == drawingBitmapWidth) return it
    }
    val bitmapShader = BitmapShader(textBitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
    val fadeRatio = (24f / drawingBitmapWidth.toFloat()).coerceIn(0.08f, 0.18f)
    val fadeShader = LinearGradient(
        0f,
        0f,
        drawingBitmapWidth.toFloat(),
        0f,
        intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
        floatArrayOf(0f, fadeRatio, 1f - fadeRatio, 1f),
        Shader.TileMode.CLAMP,
    )
    return ScrollingTextShader(
        textBitmap,
        drawingBitmapWidth,
        bitmapShader,
        ComposeShader(bitmapShader, fadeShader, PorterDuff.Mode.DST_IN),
    ).also { scrollingTextShaders[imageKey] = it }
}
```

Add a third reusable matrix to `ShareValues`:

```kotlin
private val sharedMatrix3 = Matrix()

fun sharedMatrix3(): Matrix {
    sharedMatrix3.reset()
    return sharedMatrix3
}
```

Update text cache cleanup in this exact order and expose pause behavior:

```kotlin
private fun clearTextBitmapCaches() {
    scrollingTextShaders.clear()
    scrollTimeline.clear()
    drawTextCache.values.forEach { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    }
    drawTextCache.clear()
}

fun pauseTextScrolling() {
    hasActiveScrollingText = false
    scrollTimeline.pause()
}
```

Remove `drawTextGradientCache`, `scrollTextPosition`, `cacheGradientBitmap`, `scrollTextGradientBitmap`, and `sharedPaint2`; they are replaced by the timeline and composed shader cache. In `clearCaches()`, also call `frameChangeGate.clear()`.

- [ ] **Step 5: Run tests, compile Android code, and verify GREEN**

```bash
rtk ./gradlew :library:testDebugUnitTest
rtk ./gradlew :library:assembleDebug
```

Expected: all JVM tests pass and the Android library compiles for minSdk 21 without shader API errors.

- [ ] **Step 6: Commit Task 3**

```bash
rtk git add library/src/main/java/com/opensource/svgaplayer/drawer/FrameChangeGate.kt library/src/main/java/com/opensource/svgaplayer/drawer/SVGACanvasDrawer.kt library/src/test/java/com/opensource/svgaplayer/drawer/FrameChangeGateTest.kt
rtk git commit -m "perf: render scrolling text in one pass"
```

---

### Task 4: Drawable and ImageView Lifecycle Integration

**Files:**
- Modify: `library/src/main/java/com/opensource/svgaplayer/SVGADrawable.kt:10-110`
- Modify: `library/src/main/java/com/opensource/svgaplayer/SVGAImageView.kt:75-95,196-201,284-346,434-460`

**Interfaces:**
- Consumes: `TextScrollTicker` from Task 2 and `SVGACanvasDrawer.hasActiveScrollingText` from Task 3.
- Produces internally: `SVGADrawable.setTextScrollEnabled(Boolean)` and `setTextScrollAttached(Boolean)`.
- Preserves: existing public ImageView and Drawable API signatures.

- [ ] **Step 1: Add ticker ownership to `SVGADrawable`**

Import `android.os.SystemClock` and add:

```kotlin
private var textScrollAttached = true
private var textScrollVisible = true
private val textScrollTicker = TextScrollTicker(
    nowUptimeMillis = SystemClock::uptimeMillis,
    schedule = { runnable, deadline -> scheduleSelf(runnable, deadline) },
    unschedule = { runnable -> unscheduleSelf(runnable) },
    invalidate = { invalidateSelf() },
)
```

Replace `draw` and add lifecycle methods:

```kotlin
override fun draw(canvas: Canvas) {
    if (cleared) {
        textScrollTicker.onDraw(false)
        return
    }
    drawer.drawFrame(canvas, currentFrame, scaleType)
    textScrollTicker.onDraw(drawer.hasActiveScrollingText)
}

internal fun setTextScrollEnabled(enabled: Boolean) {
    drawer.pauseTextScrolling()
    if (enabled && !cleared) {
        textScrollTicker.setEnabled(true)
    } else {
        textScrollTicker.stop()
    }
}

internal fun setTextScrollAttached(attached: Boolean) {
    textScrollAttached = attached
    if (!attached) drawer.pauseTextScrolling()
    updateTextScrollHostActive()
}

override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
    textScrollVisible = visible
    updateTextScrollHostActive()
    return super.setVisible(visible, restart)
}

private fun updateTextScrollHostActive() {
    textScrollTicker.setHostActive(textScrollAttached && textScrollVisible)
}
```

In the `cleared` setter, stop scheduling when the value becomes true:

```kotlin
if (value) {
    textScrollTicker.stop()
    drawer.pauseTextScrolling()
}
```

At the start of `clear()`, call:

```kotlin
textScrollTicker.stop()
drawer.pauseTextScrolling()
```

- [ ] **Step 2: Wire playback and static mode in `SVGAImageView`**

Add state:

```kotlin
private var isStaticVideoItem = false
```

At the end of `setupDrawable()`, enable text scrolling and mark playback mode:

```kotlin
isStaticVideoItem = false
drawable.setTextScrollEnabled(true)
```

At the start of `stopAnimation(clear: Boolean)`, keep the existing animator cleanup and add:

```kotlin
getSVGADrawable()?.setTextScrollEnabled(false)
```

Update drawable replacement so the old ticker is stopped and the new drawable receives attachment state:

```kotlin
override fun setImageDrawable(drawable: Drawable?) {
    getSVGADrawable()?.setTextScrollEnabled(false)
    super.setImageDrawable(drawable)
    if (drawable is SVGADrawable) {
        drawable.setTextScrollAttached(isAttachedToWindow)
        if (scope == null) {
            pendingLoadDrawable = drawable
        } else {
            startLoadDrawable(drawable)
        }
    } else {
        pendingLoadDrawable = null
        isStaticVideoItem = false
    }
}
```

Set animated mode in `setVideoItem` before installing the drawable:

```kotlin
isStaticVideoItem = false
```

Set static mode and enable the ticker in `setStaticVideoItem`:

```kotlin
val drawable = SVGADrawable(videoItem, dynamicItem ?: SVGADynamicEntity())
drawable.cleared = false
drawable.setTextScrollEnabled(true)
isStaticVideoItem = true
setImageDrawable(drawable)
return drawable
```

- [ ] **Step 3: Preserve static intent across attach and detach**

In `onAttachedToWindow()`, after `super.onAttachedToWindow()`, add:

```kotlin
getSVGADrawable()?.setTextScrollAttached(true)
```

In `onDetachedFromWindow()`, suspend the host before `super.onDetachedFromWindow()` and replace the unconditional stop with mode-aware cleanup:

```kotlin
val drawable = getSVGADrawable()
drawable?.setTextScrollAttached(false)
super.onDetachedFromWindow()
removeCallbacks(updateSvagDrawableCallBack)
if (isStaticVideoItem && !clearsAfterDetached) {
    drawable?.stop()
} else {
    stopAnimation(clearsAfterDetached)
}
parserImagesEndCallBack = null
if (clearsAfterDetached) {
    clear()
}
```

This preserves an already-enabled static ticker while detached, but does not override a prior manual pause. Animated scrolling remains disabled after detach until `startAnimation()` is called again.

- [ ] **Step 4: Run lifecycle logic tests and compile integration**

```bash
rtk ./gradlew :library:testDebugUnitTest --tests com.opensource.svgaplayer.TextScrollTickerTest
rtk ./gradlew :library:testDebugUnitTest
rtk ./gradlew :library:assembleDebug
```

Expected: scheduler tests pass; `SVGADrawable` and `SVGAImageView` compile without changing public signatures.

- [ ] **Step 5: Commit Task 4**

```bash
rtk git add library/src/main/java/com/opensource/svgaplayer/SVGADrawable.kt library/src/main/java/com/opensource/svgaplayer/SVGAImageView.kt
rtk git commit -m "feat: keep static text scrolling at sixty fps"
```

---

### Task 5: Full Verification and Acceptance Review

**Files:**
- Verify: all files changed in Tasks 1-4.

**Interfaces:**
- Verifies all requirements from `docs/superpowers/specs/2026-07-13-text-scroll-design.md`.
- Produces no new runtime API.

- [ ] **Step 1: Run all library tests from a clean Gradle invocation**

```bash
rtk ./gradlew :library:testDebugUnitTest --rerun-tasks
```

Expected: all tests pass with zero failures.

- [ ] **Step 2: Compile the library and sample application**

```bash
rtk ./gradlew :library:assembleDebug :app:assembleDebug
```

Expected: both modules build successfully.

- [ ] **Step 3: Inspect the final diff and whitespace**

```bash
rtk git diff --check HEAD~4
rtk git status --short
rtk git diff HEAD~4 -- library/src/main/java/com/opensource/svgaplayer/drawer/SVGACanvasDrawer.kt library/src/main/java/com/opensource/svgaplayer/SVGADrawable.kt library/src/main/java/com/opensource/svgaplayer/SVGAImageView.kt
```

Expected: no whitespace errors; only the planned production, test, build, and documentation files changed.

- [ ] **Step 4: Review acceptance criteria against evidence**

Confirm from tests and code inspection:

```text
Time-based offset is independent of draw count.
Ticker deadlines average 60 FPS.
Carousel uses one drawRect with cached shaders and no saveLayer.
Static mode enables the ticker without advancing currentFrame.
Manual pause disables the ticker and resume resets only timestamps.
Repeated text redraws do not replay frame audio.
Clear, replacement, visibility, and detach cancel pending callbacks.
```

- [ ] **Step 5: Record RTK savings and final status**

```bash
rtk gain
rtk git status --short
```

Expected: RTK reports command-output savings and the worktree contains no uncommitted implementation changes.
