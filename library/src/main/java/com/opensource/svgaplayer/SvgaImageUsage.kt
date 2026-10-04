package com.opensource.svgaplayer

import android.graphics.Matrix
import com.opensource.svgaplayer.bitmap.SvgaImageSize

/** All frames are considered so seek, replay and reverse remain safe. */
internal fun SVGAVideoEntity.imageUsage(): Map<String, SvgaImageSize> {
    val usage = HashMap<String, SvgaImageSize>()
    val matrix = FloatArray(9)
    spriteList.forEach { sprite ->
        val imageKey = sprite.imageKey ?: return@forEach
        val matte = imageKey.endsWith(".matte")
        sprite.frames.forEach { frame ->
            // Keep matte images conservatively, including transparent masks.
            frame.transform.getValues(matrix)
            usage.getOrPut(imageKey.removeSuffix(".matte")) { SvgaImageSize() }.include(
                frame.layout.width, frame.layout.height,
                matrix[Matrix.MSCALE_X].toDouble(), matrix[Matrix.MSKEW_Y].toDouble(),
                matrix[Matrix.MSKEW_X].toDouble(), matrix[Matrix.MSCALE_Y].toDouble(),
                visible = matte || !(frame.alpha <= 0.0))
        }
    }
    return usage
}
