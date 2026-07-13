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
