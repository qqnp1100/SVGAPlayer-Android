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
