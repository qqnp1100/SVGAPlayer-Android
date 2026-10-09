package com.opensource.svgaplayer.glide5

import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy

/** Cover both layer axes while bounding the source's longest decoded edge and avoiding upscaling. */
internal class SvgaDownsampleStrategy(private val limit: Int) : DownsampleStrategy() {
    override fun getScaleFactor(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): Float =
        minOf(1.0, maxOf(requestedWidth.toDouble() / sourceWidth, requestedHeight.toDouble() / sourceHeight),
            limit.toDouble() / maxOf(sourceWidth, sourceHeight)).toFloat()
    override fun getSampleSizeRounding(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int) =
        SampleSizeRounding.QUALITY

    override fun equals(other: Any?) = other is SvgaDownsampleStrategy && limit == other.limit
    override fun hashCode() = 31 * javaClass.name.hashCode() + limit
}
