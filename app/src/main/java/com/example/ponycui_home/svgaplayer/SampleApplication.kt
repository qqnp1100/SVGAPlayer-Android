package com.example.ponycui_home.svgaplayer

import android.app.Application
import android.util.Log
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.loadSvga
import com.opensource.svgaplayer.loader.SvgaSource

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Route XML app:source through the same 3.0.0 loader as programmatic Views.
        SVGAImageView.sourceLoader = { view, source, shouldAutoPlay ->
            val svgaSource = if (source.contains("://")) {
                SvgaSource.from(source)
            } else {
                SvgaSource.Asset(source)
            }
            view.loadSvga(svgaSource) {
                useViewControls = true // Preserve XML loopCount and fillMode.
                autoPlay = shouldAutoPlay
                onError = { Log.e("SvgaXml", "XML source load failed: $source", it) }
            }
        }
    }
}
