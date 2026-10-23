package com.opensource.svgaplayer

import android.graphics.Bitmap

/** Options for the immutable base images. Dynamic bindings have their own image loader. */
data class SvgaDecodeOptions(
    /** Preferred format. Images with alpha retain alpha even when RGB_565 is requested. */
    val bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    /** Skip unused / always-transparent image layers across the entire animation. */
    val skipInvisibleImages: Boolean = false,
    /** Resource loader: defer images with exactly one visible layer/frame reference. */
    val inBitmap: Boolean = false,
    /** Decode shared images when their first visible frame is prepared. */
    val progressive: Boolean = false,
    /** Multiply the existing base-bitmap target dimensions by this scale, in (0, 1]. */
    val renderScale: Float = 1f,
) {
    init {
        require(renderScale.isFinite() && renderScale > 0f && renderScale <= 1f) {
            "SVGA renderScale must be finite and in (0, 1]"
        }
        require(bitmapConfig == Bitmap.Config.ARGB_8888 || bitmapConfig == Bitmap.Config.RGB_565) {
            "SVGA bitmapConfig must be ARGB_8888 or RGB_565"
        }
    }

    companion object {
        /** Process-wide defaults, captured once per load. Existing resources are not changed. */
        @Volatile
        @JvmStatic
        var defaults = SvgaDecodeOptions()
    }
}
