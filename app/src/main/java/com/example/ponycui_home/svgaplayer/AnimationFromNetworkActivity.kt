package com.example.ponycui_home.svgaplayer

import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.loadSvga

class AnimationFromNetworkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val animationView = SVGAImageView(this).apply {
            setBackgroundColor(Color.GRAY)
        }
        setContentView(animationView)
        animationView.loadSvga("https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true") {
            onReady = { Log.d("SvgaNetwork", "Animation ready") }
            onError = { Log.e("SvgaNetwork", "Network load failed", it) }
        }
    }
}
