package com.opensource.svgaplayer.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.opensource.svgaplayer.coil3.SvgaBindings

/** Compose-friendly text types stay in the optional Compose module. */
fun SvgaBindings.Builder.text(key: String, value: String, fontSize: TextUnit = 16.sp,
    color: Color, maxLines: Int = 1, ellipsize: Boolean = true, scrollPixelsPerSecond: Float = 0f) {
    require(fontSize.isSp) { "SVGA fontSize must use sp" }
    text(key, value, fontSize.value, color.toArgb(), scrollPixelsPerSecond, maxLines, ellipsize)
}
