package com.opensource.svgaplayer.coil3

import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextPaint
import android.widget.ImageView
import coil3.ImageLoader
import coil3.request.*
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import com.opensource.svgaplayer.utils.SVGAScaleInfo
import kotlinx.coroutines.*

class SvgaBindings private constructor(private val actions: List<suspend (SVGADynamicEntity, ImageLoader, android.content.Context, SvgaCachePolicy, SvgaResource?, (String) -> android.util.Size?) -> Unit>) {
    suspend fun prepare(loader: ImageLoader, context: android.content.Context, cache: SvgaCachePolicy, resource: SvgaResource? = null): SVGADynamicEntity =
        prepare(loader, context, cache, resource, 0, 0)

    suspend fun prepare(loader: ImageLoader, context: android.content.Context, cache: SvgaCachePolicy,
        resource: SvgaResource?, width: Int, height: Int, scaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_XY): SVGADynamicEntity {
        val scale = SVGAScaleInfo()
        if (resource != null && width > 0 && height > 0) {
            scale.performScaleType(width.toFloat(), height.toFloat(), resource.width.toFloat(), resource.height.toFloat(), scaleType)
        }
        val imageSizes = HashMap<String, android.util.Size?>()
        val imageSize: (String) -> android.util.Size? = { key ->
            imageSizes.getOrPut(key) { resource?.layerImageSize(key, scale.scaleFx, scale.scaleFy) }
        }
        val dynamic = SVGADynamicEntity()
        try { for (action in actions) { currentCoroutineContext().ensureActive(); action(dynamic, loader, context, cache, resource, imageSize) }; return dynamic }
        catch (e: Throwable) { dynamic.clearDynamicObjects(); throw e }
    }
    class Builder {
        internal val actions = ArrayList<suspend (SVGADynamicEntity, ImageLoader, android.content.Context, SvgaCachePolicy, SvgaResource?, (String) -> android.util.Size?) -> Unit>()
        fun text(key: String, value: String, textSizeSp: Float = 16f, color: Int = Color.WHITE, scrollPixelsPerSecond: Float = 0f,
                 maxLines: Int = 1, ellipsize: Boolean = true, alignment: android.text.Layout.Alignment = android.text.Layout.Alignment.ALIGN_CENTER,
                 typeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT,
                 scrollSpacing: Float = 10f) {
            require(textSizeSp > 0 && maxLines > 0)
            actions += { dynamic, _, context, _, resource, _ ->
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
        fun image(key: String, bitmap: Bitmap) { actions += { dynamic, _, _, _, _, _ -> dynamic.setDynamicImage(bitmap, key) } }
        fun text(key: String, value: String, paint: TextPaint) {
            val snapshot = TextPaint(paint)
            actions += { dynamic, _, _, _, _, _ -> dynamic.setDynamicText(value, snapshot, key) }
        }
        fun text(key: String, layout: android.text.StaticLayout) {
            actions += { dynamic, _, _, _, _, _ -> dynamic.setDynamicText(layout, key) }
        }
        fun textScroll(key: String, pixelsPerSecond: Float, spacing: Float = 10f) {
            actions += { dynamic, _, _, _, _, _ ->
                dynamic.srcollTextSpace = spacing
                dynamic.setDynamicTextScrollSpeed(key, pixelsPerSecond)
            }
        }
        /** Decode for the displayed layer; size optionally caps its longest edge, up to 1024 pixels. */
        fun image(key: String, source: Any, size: Int = 0, required: Boolean = true, circleCrop: Boolean = false) {
            require(size >= 0)
            actions += { dynamic, loader, context, cache, _, imageSize ->
                val layer = imageSize(key)
                val target = SvgaDynamicImageSize.resolve(layer?.width, layer?.height, size, circleCrop)
                val limit = SvgaDynamicImageSize.limit(size)
                val request = ImageRequest.Builder(context).data(source).size(target.width, target.height)
                    .scale(Scale.FILL).precision(Precision.EXACT).allowHardware(false)
                    .decoderFactory(SvgaDynamicImageDecoder(target, limit))
                    .memoryCacheKeyExtra("svga.dynamicImageSizing", "fill:$limit")
                    .memoryCachePolicy(if (cache.memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    .diskCachePolicy(if (cache.disk) CachePolicy.ENABLED else CachePolicy.DISABLED)
                if (circleCrop) request.transformations(SvgaCircleCropTransformation(target.width))
                val result = loader.execute(request.build())
                when (result) {
                    is SuccessResult -> dynamic.setDynamicImage(result.image.toBitmap(), key)
                    is ErrorResult -> if (required) throw result.throwable
                }
            }
        }
        fun hidden(key: String, hidden: Boolean = true) { actions += { d, _, _, _, _, _ -> d.setHidden(hidden, key) } }
        fun build() = SvgaBindings(actions.toList())
    }
    companion object { val Empty = Builder().build() }
}
fun svgaBindings(block: SvgaBindings.Builder.() -> Unit) = SvgaBindings.Builder().apply(block).build()
