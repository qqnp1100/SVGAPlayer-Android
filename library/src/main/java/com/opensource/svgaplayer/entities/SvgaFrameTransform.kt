package com.opensource.svgaplayer.entities

import android.graphics.Matrix

/** Six affine coefficients, without a native Matrix allocation or a retained FloatArray. */
internal data class SvgaFrameTransform(
    val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float,
) {
    fun getValues(values: FloatArray) {
        values[0] = a; values[1] = c; values[2] = tx
        values[3] = b; values[4] = d; values[5] = ty
        values[6] = 0f; values[7] = 0f; values[8] = 1f
    }

    fun copyTo(matrix: Matrix, values: FloatArray) {
        getValues(values)
        matrix.setValues(values)
    }

    companion object {
        val IDENTITY = SvgaFrameTransform(1f, 0f, 0f, 1f, 0f, 0f)
    }
}
