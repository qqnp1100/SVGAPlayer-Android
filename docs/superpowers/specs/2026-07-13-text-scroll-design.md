# Dynamic Text Scrolling Design

## Goal

Improve `drawTextOnBitmap` so long dynamic text loops efficiently at a fixed target of 60 FPS, advances at a stable real-time speed, and continues to scroll when displayed through `setStaticVideoItem`.

## Confirmed Behavior

- Long text scrolls only when its measured width exceeds the drawing region and its configured speed is greater than zero.
- Scrolling targets 60 refreshes per second independently of the SVGA file's FPS.
- `setDynamicTextScrollSpeed` and `srcollTextSpace` keep their existing visual meaning and remain source-compatible.
- `setStaticVideoItem` keeps the selected SVGA frame static while dynamic text continues to scroll.
- `startAnimation()` enables text scrolling together with SVGA playback.
- A manual `pauseAnimation()` or `stopAnimation(false)` pauses text scrolling at its current offset.
- A later `startAnimation()` resumes from the saved offset without counting paused time.
- `stopAnimation(true)`, `clear()`, or replacement of the drawable stops scheduled text refreshes and releases scrolling resources.
- Text-only refreshes do not advance `currentFrame`, play audio, or invoke animation step and completion callbacks.

## Architecture

The feature is split into three focused responsibilities:

1. `SVGACanvasDrawer` detects active scrolling text and renders it. It captures one monotonic frame timestamp per draw and uses that timestamp for every text layer in the frame.
2. A small pure-Kotlin scroll timeline owns per-key offsets and timestamps. It converts elapsed time into distance, wraps offsets at the cycle width, and resets timing across pauses without discarding the saved position.
3. `SVGADrawable` owns a fixed-rate refresh ticker. `SVGAImageView` controls whether that ticker is enabled according to static, playing, paused, stopped, cleared, attached, and replacement lifecycle states.

This separation keeps Android drawing code out of the timing tests and prevents the text refresh loop from changing SVGA animation semantics.

## Scroll Timing

The old implementation advances the offset once per call to `drawFrame`, so its speed changes when drawing occurs more or less often than the SVGA FPS. The new timeline advances by elapsed monotonic time:

```text
localPixelsPerSecond = configuredSpeed * videoWidth * mappedXScale / canvasWidth
newOffset = (oldOffset + localPixelsPerSecond * elapsedSeconds) % cycleWidth
```

This is equivalent to the old visual speed when the old implementation rendered at the declared SVGA FPS, but remains stable at the new 60 FPS refresh rate.

The first draw after creation or resume establishes a timestamp and does not add paused time. An elapsed interval is capped at 100 milliseconds so a temporary rendering stall does not cause a large visible jump. Invalid or non-positive dimensions, cycle widths, elapsed intervals, and speeds leave the saved offset unchanged.

## Fixed 60 FPS Ticker

The ticker maintains an absolute monotonic deadline using a `1000 / 60` millisecond fractional interval. Converting each accumulated deadline to the next whole uptime millisecond naturally alternates 16 and 17 millisecond delays instead of drifting at a constant 16 or 17 milliseconds.

After a draw, `SVGACanvasDrawer` reports whether at least one visible text layer genuinely needs scrolling. The ticker schedules another invalidation only when all of these conditions hold:

- text scrolling is enabled by the owning `SVGAImageView`;
- the drawable is visible, attached to a callback, and not cleared;
- the last draw found active overflowing text.

The ticker invalidates only the drawable. It never changes `currentFrame`. If a later draw finds no active scrolling text, the pending ticker callback is cancelled.

For a static item, scrolling is enabled immediately. For an animated item, `setupDrawable()` enables it when playback begins. Manual pause or stop disables the ticker and resets only timing anchors. Resume enables it again and retains offsets. Detach suspends scheduling while preserving the desired enabled state so attachment can resume static or playing text without a time jump.

## Carousel Rendering

The existing loop draws the same text bitmap repeatedly and applies a gradient with an offscreen `saveLayer` on every frame. The replacement uses shaders in the text layer's local coordinate space:

- A cached `BitmapShader` uses horizontal `REPEAT` and vertical `CLAMP` tile modes.
- A small local matrix translates the bitmap shader by the current negative offset.
- A cached `LinearGradient` supplies the existing transparent edge fade.
- A `ComposeShader` combines the repeated text and fade mask with `DST_IN`.
- One `drawRect` fills the clipped text viewport.

The text bitmap already includes `srcollTextSpace`, so repeating it produces a seamless text-gap-text cycle. This path performs one draw call, avoids the per-frame bitmap loop, and removes the per-frame offscreen layer. Shader objects are cached alongside the text bitmap and are discarded whenever text bitmap caches are invalidated or cleared.

The existing frame transform, alpha, anti-aliasing, clipping rectangle, and optional SVGA mask path remain in effect. Short or non-scrolling text continues through the current single-bitmap path.

## State and Cache Handling

Per-key state contains only the current offset and last active timestamp. Text changes clear the associated bitmap, shader, gradient, offset, and timestamp state together so stale dimensions cannot affect a replacement string.

Cache cleanup continues to recycle owned bitmaps. Shader caches only reference those owned bitmaps and are cleared before bitmap recycling. No bitmap or shader allocation occurs on steady-state scrolling frames; only the shader matrix and offset are updated.

## Failure Handling

- Zero-sized canvases or drawing regions return without scheduling another text frame.
- Non-finite or non-positive speeds do not start the ticker.
- Non-finite transforms fall back to the existing safe mapped scale behavior.
- Recycled or unavailable text bitmaps do not enter the shader path.
- Clearing, replacing, hiding, or detaching a drawable cancels pending callbacks so the ticker cannot retain the view or continue consuming UI-thread time.

## Testing

Pure JVM tests cover the scroll timeline and ticker without requiring an emulator:

- 30, 60, and 120 timing samples travel the same distance for the same elapsed time.
- The offset wraps exactly at the cycle width and remains non-negative.
- The first draw and the first draw after resume preserve the saved offset.
- A long delay is capped and cannot produce a large jump.
- Invalid speed, cycle width, and elapsed time preserve the current offset.
- Active overflowing text starts the 60 FPS ticker with alternating 16/17 millisecond deadlines.
- Short text, pause, clear, replacement, and detach cancel scheduling.
- Re-enabling after pause requires a fresh draw and resumes from the saved offset.

Build verification covers Android API compatibility of `BitmapShader`, `ComposeShader`, drawable scheduling, and the modified visibility lifecycle. Final verification runs the library unit tests, compiles the library, and runs `git diff --check`.

## Non-Goals

- No public scrolling API is renamed or added.
- The direction, edge-fade appearance, text layout, and gap configuration are not redesigned.
- SVGA frame timing, audio playback, callbacks, and static frame selection are not changed.
- The refresh target is fixed at 60 FPS rather than following 90 Hz or 120 Hz display refresh rates.
