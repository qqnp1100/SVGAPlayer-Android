package com.opensource.svgaplayer.coil3

import org.junit.Assert.assertEquals
import org.junit.Test

class SvgaDynamicImageSizeTest {
    @Test fun mismatchedSourceAspectRatioCoversBothDisplayedAxes() {
        val target = SvgaDynamicImageSize(80, 20)
        assertEquals(80, target.decodeEdge(1024, 1024, 1024))
        assertEquals(80, target.decodeEdge(1024, 256, 1024))
        assertEquals(160, target.decodeEdge(1024, 128, 1024))
        assertEquals(320, target.decodeEdge(256, 1024, 1024))
        assertEquals(80, SvgaDynamicImageSize(40, 40).decodeEdge(1024, 512, 1024))
    }

    @Test fun sourceAwareDecodeRemainsBoundedAndNeverUpscalesSmallSources() {
        val target = SvgaDynamicImageSize(80, 1000)
        assertEquals(1024, target.decodeEdge(Int.MAX_VALUE, 1, 1024))
        assertEquals(40, target.decodeEdge(8192, 1, 40))
        assertEquals(16, target.decodeEdge(16, 8, 1024))
    }

    @Test fun rectangularFillKeepsIndependentDimensions() {
        assertEquals(SvgaDynamicImageSize(80, 20), SvgaDynamicImageSize.resolve(80, 20, 0, false))
        assertEquals(SvgaDynamicImageSize(80, 80), SvgaDynamicImageSize.resolve(80, 20, 0, true))
    }

    @Test fun explicitSizeCapsTheRegionInsteadOfForcingAnUpscale() {
        assertEquals(SvgaDynamicImageSize(80, 20), SvgaDynamicImageSize.resolve(80, 20, 8192, false))
        assertEquals(SvgaDynamicImageSize(40, 10), SvgaDynamicImageSize.resolve(80, 20, 40, false))
        assertEquals(SvgaDynamicImageSize(40, 40), SvgaDynamicImageSize.resolve(80, 20, 40, true))
    }

    @Test fun hugeRegionsAndExplicitFallbacksStayBounded() {
        assertEquals(SvgaDynamicImageSize(1024, 512), SvgaDynamicImageSize.resolve(8192, 4096, 0, false))
        assertEquals(SvgaDynamicImageSize(1024, 1), SvgaDynamicImageSize.resolve(Int.MAX_VALUE, 1, Int.MAX_VALUE, false))
        assertEquals(SvgaDynamicImageSize(1024, 1024), SvgaDynamicImageSize.resolve(null, null, 8192, true))
    }

    @Test fun missingLayerUsesTheBoundedExplicitFallback() {
        assertEquals(SvgaDynamicImageSize(1, 1), SvgaDynamicImageSize.resolve(null, null, 0, false))
        assertEquals(SvgaDynamicImageSize(128, 128), SvgaDynamicImageSize.resolve(null, null, 128, false))
        assertEquals(SvgaDynamicImageSize(16, 16), SvgaDynamicImageSize.resolve(0, 0, 16, false))
    }
}
