package com.opensource.svgaplayer.bitmap

import kotlin.math.ceil
import kotlin.math.hypot

/** Maximum displayed dimensions for every visible use of one normalized image key. */
internal class SvgaImageSize {
    private var width = 0.0
    private var height = 0.0
    private var invalid = false
    var hasVisibleFrame = false
        private set
    var visibleUseCount = 0
        private set
    var singleUseFrame: Int? = null
        private set

    fun include(layoutWidth: Double, layoutHeight: Double, a: Double, b: Double, c: Double, d: Double,
                visible: Boolean = true, frameIndex: Int = -1) {
        if (visible) {
            visibleUseCount = (visibleUseCount + 1).coerceAtMost(2)
            singleUseFrame = if (visibleUseCount == 1) frameIndex else null
        }
        if (hasVisibleFrame && !visible) return
        if (visible && !hasVisibleFrame) {
            // Retain invisible-frame sizing only as a fallback when filtering is disabled.
            width = 0.0; height = 0.0; invalid = false
            hasVisibleFrame = true
        }
        val nextWidth = layoutWidth * hypot(a, b)
        val nextHeight = layoutHeight * hypot(c, d)
        if (!nextWidth.isFinite() || !nextHeight.isFinite() || nextWidth < 0 || nextHeight < 0) {
            invalid = true
        } else {
            width = maxOf(width, nextWidth)
            height = maxOf(height, nextHeight)
        }
    }

    fun target(sourceWidth: Int, sourceHeight: Int, viewportScale: Double): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0)
        if (invalid || !viewportScale.isFinite() || viewportScale <= 0) return sourceWidth to sourceHeight
        // Use a uniform conservative scale, covering rotation, mirroring and non-uniform viewports.
        val scale = maxOf(width * viewportScale / sourceWidth, height * viewportScale / sourceHeight).coerceIn(0.0, 1.0)
        return maxOf(1, ceil(sourceWidth * scale).toInt()) to maxOf(1, ceil(sourceHeight * scale).toInt())
    }

    companion object {
        fun target(usage: SvgaImageSize?, sourceWidth: Int, sourceHeight: Int, viewportScale: Double): Pair<Int, Int> =
            (usage ?: SvgaImageSize().apply {
                include(sourceWidth.toDouble(), sourceHeight.toDouble(), 1.0, 0.0, 0.0, 1.0)
            }).target(sourceWidth, sourceHeight, viewportScale)
    }
}
