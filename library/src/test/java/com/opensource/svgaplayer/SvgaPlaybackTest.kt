package com.opensource.svgaplayer

import org.junit.Assert.*
import org.junit.Test

class SvgaPlaybackTest {
    @Test fun seekFrameIsExactAtFractionalFrameDurations() {
        for (fps in listOf(12, 24, 29, 30, 60)) {
            for (reverse in listOf(false, true)) {
                val clock = SvgaPlayback(100, fps, 3, 97, reverse)
                for (frame in 3..97) {
                    clock.seekFrame(frame)
                    assertEquals("fps=$fps reverse=$reverse", frame, clock.frame)
                    clock.tick(123L)
                    assertEquals(frame, clock.frame)
                }
            }
        }
    }

    @Test fun sixtyFpsHasExactSecondAndFinishesOnce() {
        val clock = SvgaPlayback(60, 60)
        clock.tick(0, 1)
        assertFalse(clock.tick(999_999_999, 1))
        assertEquals(59, clock.frame)
        assertTrue(clock.tick(1_000_000_000, 1))
        assertFalse(clock.tick(2_000_000_000, 1))
        assertEquals(1, clock.completedIterations)
    }
    @Test fun pauseExcludesWallTimeAndSeekDoesNotReload() {
        val clock = SvgaPlayback(60, 60)
        clock.tick(0); clock.tick(500_000_000)
        clock.pause(); clock.tick(20_000_000_000)
        assertEquals(30, clock.frame)
        clock.seek(.25f)
        assertEquals(15, clock.frame)
        clock.tick(30_000_000_000); clock.tick(30_100_000_000)
        assertEquals(21, clock.frame)
    }
    @Test fun droppedFramesSkipToCorrectLoop() {
        val clock = SvgaPlayback(30, 30)
        clock.tick(0); clock.tick(3_500_000_000)
        assertEquals(3, clock.completedIterations)
        assertEquals(15, clock.frame)
    }
    @Test fun reverseRangeAndSpeedUseRequestedFrames() {
        val clock = SvgaPlayback(100, 20, 10, 29, true)
        clock.tick(0, 1, 2f)
        assertEquals(29, clock.frame)
        clock.tick(250_000_000, 1, 2f)
        assertEquals(19, clock.frame)
        assertTrue(clock.tick(500_000_000, 1, 2f))
        assertEquals(10, clock.frame)
    }
}
