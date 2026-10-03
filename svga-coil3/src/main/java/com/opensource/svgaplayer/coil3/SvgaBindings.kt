package com.opensource.svgaplayer.coil3

import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextPaint
import coil3.ImageLoader
import coil3.request.*
import coil3.toBitmap
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import kotlinx.coroutines.*

class SvgaBindings private constructor(private val actions: List<suspend (SVGADynamicEntity, ImageLoader, android.content.Context, SvgaCachePolicy, SvgaResource?) -> Unit>) {
    suspend fun prepare(loader: ImageLoader, context: android.content.Context, cache: SvgaCachePolicy, resource: SvgaResource? = null): SVGADynamicEntity {
        val dynamic = SVGADynamicEntity()
        try { for (action in actions) { currentCoroutineContext().ensureActive(); action(dynamic, loader, context, cache, resource) }; return dynamic }
        catch (e: Throwable) { dynamic.clearDynamicObjects(); throw e }
    }
    class Builder {
        internal val actions = ArrayList<suspend (SVGADynamicEntity, ImageLoader, android.content.Context, SvgaCachePolicy, SvgaResource?) -> Unit>()
        fun text(key: String, value: String, textSizeSp: Float = 16f, color: Int = Color.WHITE, scrollPixelsPerSecond: Float = 0f,
                 maxLines: Int = 1, ellipsize: Boolean = true, alignment: android.text.Layout.Alignment = android.text.Layout.Alignment.ALIGN_CENTER,
                 typeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT,
                 scrollSpacing: Float = 10f) {
            require(textSizeSp > 0 && maxLines > 0)
            actions += { dynamic, _, context, _, resource ->
                val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = textSizeSp * context.resources.displayMetrics.scaledDensity; this.color = color; this.typeface = typeface
                }
                val width = resource?.layerSize(key)?.width ?: 0
                if (width > 0 && scrollPixelsPerSecond <= 0 && android.os.Build.VERSION.SDK_INT >= 23) {
                    val layout = withContext(Dispatchers.Default) {
                        android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                            .setMaxLines(maxLines).setAlignment(alignment).setIncludePad(false)
                            .setEllipsize(if (ellipsize) android.text.TextUtils.TruncateAt.END else null).build()
                    }
                    dynamic.setDynamicText(layout, key)
                } else dynamic.setDynamicText(value, paint, key)
                dynamic.srcollTextSpace = scrollSpacing
                dynamic.setDynamicTextScrollSpeed(key, scrollPixelsPerSecond)
            }
        }
        fun image(key: String, bitmap: Bitmap) { actions += { dynamic, _, _, _, _ -> dynamic.setDynamicImage(bitmap, key) } }
        fun text(key: String, value: String, paint: TextPaint) {
            val snapshot = TextPaint(paint)
            actions += { dynamic, _, _, _, _ -> dynamic.setDynamicText(value, snapshot, key) }
        }
        fun text(key: String, layout: android.text.StaticLayout) {
            actions += { dynamic, _, _, _, _ -> dynamic.setDynamicText(layout, key) }
        }
        fun textScroll(key: String, pixelsPerSecond: Float, spacing: Float = 10f) {
            actions += { dynamic, _, _, _, _ ->
                dynamic.srcollTextSpace = spacing
                dynamic.setDynamicTextScrollSpeed(key, pixelsPerSecond)
            }
        }
        fun image(key: String, source: Any, size: Int = 0, required: Boolean = true, circleCrop: Boolean = false) {
            require(size >= 0)
            actions += { dynamic, loader, context, cache, resource ->
                val layer = resource?.layerSize(key)
                val target = if (size > 0) size else maxOf(layer?.width ?: 0, layer?.height ?: 0).coerceIn(1, 1024)
                val result = loader.execute(ImageRequest.Builder(context).data(source).size(target).allowHardware(false)
                    .memoryCachePolicy(if (cache.memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    .diskCachePolicy(if (cache.disk) CachePolicy.ENABLED else CachePolicy.DISABLED).build())
                when (result) {
                    is SuccessResult -> {
                        val bitmap = result.image.toBitmap()
                        val image = if (circleCrop) withContext(Dispatchers.Default) {
                            Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888).apply {
                                val shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                                val scale = maxOf(target.toFloat() / bitmap.width, target.toFloat() / bitmap.height)
                                shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale)
                                    postTranslate((target - bitmap.width * scale) / 2, (target - bitmap.height * scale) / 2) })
                                android.graphics.Canvas(this).drawCircle(target / 2f, target / 2f, target / 2f,
                                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
                            }
                        } else bitmap
                        dynamic.setDynamicImage(image, key)
                    }
                    is ErrorResult -> if (required) throw result.throwable
                }
            }
        }
        fun hidden(key: String, hidden: Boolean = true) { actions += { d, _, _, _, _ -> d.setHidden(hidden, key) } }
        fun build() = SvgaBindings(actions.toList())
    }
    companion object { val Empty = Builder().build() }
}
fun svgaBindings(block: SvgaBindings.Builder.() -> Unit) = SvgaBindings.Builder().apply(block).build()
