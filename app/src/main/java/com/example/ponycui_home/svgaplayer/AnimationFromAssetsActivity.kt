package com.example.ponycui_home.svgaplayer

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.loadSvga
import com.opensource.svgaplayer.loader.SvgaSource


class AnimationFromAssetsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val animationView = SVGAImageView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(animationView)
        val handle = animationView.loadSvga(SvgaSource.Asset("mp3_to_long.svga")) {
            onReady = { Log.d("SvgaAssets", "Animation ready") }
            onError = { Log.e("SvgaAssets", "Asset load failed", it) }
        }
        animationView.setOnClickListener {
            handle.replay()
        }
    }
}
