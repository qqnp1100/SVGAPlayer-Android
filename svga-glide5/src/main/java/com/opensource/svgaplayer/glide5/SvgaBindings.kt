package com.opensource.svgaplayer.glide5

import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextPaint
import android.widget.ImageView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.bumptech.glide.RequestManager
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import com.opensource.svgaplayer.utils.SVGAScaleInfo
import kotlinx.coroutines.*

class SvgaBindings private constructor(private val actions: List<suspend (SVGADynamicEntity, RequestManager, android.content.Context, SvgaCachePolicy, SvgaResource?, (String) -> android.util.Size?) -> Unit>) {
    suspend fun prepare(loader: RequestManager, context: android.content.Context, cache: SvgaCachePolicy, resource: SvgaResource? = null): SVGADynamicEntity =
        prepare(loader, context, cache, resource, 0, 0)

    suspend fun prepare(loader: RequestManager, context: android.content.Context, cache: SvgaCachePolicy,
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
        internal val actions = ArrayList<suspend (SVGADynamicEntity, RequestManager, android.content.Context, SvgaCachePolicy, SvgaResource?, (String) -> android.util.Size?) -> Unit>()
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
            actions += { dynamic, loader, _, cache, _, imageSize ->
                val layer = imageSize(key)
                val target = SvgaDynamicImageSize.resolve(layer?.width, layer?.height, size, circleCrop)
                val limit = SvgaDynamicImageSize.limit(size)
                val request = loader.asBitmap().load(source).override(target.width, target.height)
                    .disallowHardwareConfig().skipMemoryCache(!cache.memory)
                    .diskCacheStrategy(if (cache.disk) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE)
                    .signature(ObjectKey("svga.dynamic.fill:$limit"))
                    .dontTransform()
                    .let { if (circleCrop) it.circleCrop() else it.transform(SvgaBoundedTransformation(limit)) }
                    .downsample(SvgaDownsampleStrategy(limit))
                try {
                    withGlideResource(loader, request) { bitmap ->
                        // Glide can pool its result as soon as the target is cleared. Own a private copy.
                        dynamic.setOwnedDynamicImage(checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)), key)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (required) throw e }
            }
        }
        fun hidden(key: String, hidden: Boolean = true) { actions += { d, _, _, _, _, _ -> d.setHidden(hidden, key) } }
        fun build() = SvgaBindings(actions.toList())
    }
    companion object { val Empty = Builder().build() }
}
fun svgaBindings(block: SvgaBindings.Builder.() -> Unit) = SvgaBindings.Builder().apply(block).build()
