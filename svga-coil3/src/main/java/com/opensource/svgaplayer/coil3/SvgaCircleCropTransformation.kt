package com.opensource.svgaplayer.coil3

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import coil3.size.Size
import coil3.transform.Transformation

/** Coil owns and caches the cropped result; input pixels can be shared by other requests. */
internal class SvgaCircleCropTransformation(private val diameter: Int) : Transformation() {
    init { require(diameter in 1..SvgaDynamicImageSize.MAX_DIMENSION) }
    override val cacheKey = "svga.circleCrop:$diameter"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888).apply {
            val shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(diameter.toFloat() / input.width, diameter.toFloat() / input.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((diameter - input.width * scale) / 2, (diameter - input.height * scale) / 2)
            })
            Canvas(this).drawCircle(diameter / 2f, diameter / 2f, diameter / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        }
}
