package com.opensource.svgaplayer.glide5

import kotlin.math.ceil

/** Bound automatic and explicit requests without inflating rectangular layers into squares. */
internal data class SvgaDynamicImageSize(val width: Int, val height: Int) {
    companion object {
        const val MAX_DIMENSION = 1024

        fun limit(size: Int): Int {
            require(size >= 0)
            return if (size > 0) minOf(size, MAX_DIMENSION) else MAX_DIMENSION
        }

        fun resolve(layerWidth: Int?, layerHeight: Int?, size: Int, circleCrop: Boolean): SvgaDynamicImageSize {
            require(size >= 0)
            val limit = limit(size)
            val hasLayer = layerWidth != null && layerWidth > 0 && layerHeight != null && layerHeight > 0
            val width = if (hasLayer) layerWidth!! else size.coerceIn(1, MAX_DIMENSION)
            val height = if (hasLayer) layerHeight!! else width
            val scale = minOf(1.0, limit.toDouble() / maxOf(width, height))
            val targetWidth = ceil(width * scale).toInt().coerceIn(1, limit)
            val targetHeight = ceil(height * scale).toInt().coerceIn(1, limit)
            return if (circleCrop) {
                val diameter = maxOf(targetWidth, targetHeight)
                SvgaDynamicImageSize(diameter, diameter)
            } else SvgaDynamicImageSize(targetWidth, targetHeight)
        }
    }
}
