package com.opensource.svgaplayer.bitmap

import org.junit.Assert.assertEquals
import org.junit.Test

class SvgaImageSizeTest {
    @Test fun usesLayoutAndSubunitTransformWithoutPowerOfTwoRounding() {
        val usage = SvgaImageSize()
        usage.include(1200.0, 1200.0, .5, 0.0, 0.0, .5)
        assertEquals(600 to 600, usage.target(1024, 1024, 1.0))
        assertEquals(300 to 300, usage.target(1024, 1024, .5))
    }

    @Test fun mergesEveryUseIncludingRotatedAndMirroredLayers() {
        val usage = SvgaImageSize()
        usage.include(200.0, 100.0, 0.0, -2.0, -2.0, 0.0)
        usage.include(10.0, 10.0, .1, 0.0, 0.0, .1)
        assertEquals(400 to 200, usage.target(800, 400, 1.0))
    }

    @Test fun preservesAspectRatioRoundsUpAndNeverUpscales() {
        val usage = SvgaImageSize()
        usage.include(100.1, 20.0, 1.0, 0.0, 0.0, 1.0)
        assertEquals(101 to 51, usage.target(200, 100, 1.0))
        assertEquals(200 to 100, usage.target(200, 100, 100.0))
    }

    @Test fun unknownViewportAndInvalidTransformsPreserveSourcePixels() {
        val usage = SvgaImageSize()
        usage.include(20.0, 20.0, .5, 0.0, 0.0, .5)
        assertEquals(200 to 100, usage.target(200, 100, Double.NaN))
        usage.include(20.0, 20.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0)
        assertEquals(200 to 100, usage.target(200, 100, 1.0))
    }

    @Test fun invisibleFramesDoNotInflateVisibleUsesButHaveSamplingFallback() {
        val usage = SvgaImageSize()
        usage.include(800.0, 800.0, 1.0, 0.0, 0.0, 1.0, visible = false)
        assertEquals(false, usage.hasVisibleFrame)
        assertEquals(400 to 400, usage.target(1000, 1000, .5))
        usage.include(100.0, 100.0, 1.0, 0.0, 0.0, 1.0)
        usage.include(900.0, 900.0, 1.0, 0.0, 0.0, 1.0, visible = false)
        assertEquals(true, usage.hasVisibleFrame)
        assertEquals(50 to 50, usage.target(1000, 1000, .5))
        assertEquals(500 to 250, SvgaImageSize.target(null, 1000, 500, .5))
    }
}
