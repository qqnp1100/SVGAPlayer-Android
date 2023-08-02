package com.example.ponycui_home.svgaplayer

import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser.Companion.shareParser
import com.opensource.svgaplayer.SVGAParser.ParseCompletion
import com.opensource.svgaplayer.SVGAVideoEntity
import java.net.MalformedURLException
import java.net.URL

class AnimationFromNetworkActivity : AppCompatActivity() {
    var animationView: SVGAImageView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animationView = SVGAImageView(this)
        animationView!!.setBackgroundColor(Color.GRAY)
        setContentView(animationView)
        loadAnimation();
    }

    private fun loadAnimation() {
        try { // new URL needs try catch.
            val svgaParser = shareParser()
            svgaParser.setFrameSize(100, 100)
            svgaParser.decodeFromURL(
                URL("https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true"),
                object : ParseCompletion {
                    override fun onComplete(videoItem: SVGAVideoEntity) {
                        Log.d("##", "## FromNetworkActivity load onComplete")
                        animationView!!.setVideoItem(videoItem)
                        animationView!!.startAnimation()
                    }

                    override fun onError() {}
                },
                null
            )
        } catch (e: MalformedURLException) {
            e.printStackTrace()
        }
    }
}