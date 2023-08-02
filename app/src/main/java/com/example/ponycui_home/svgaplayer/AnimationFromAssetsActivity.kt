package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser.Companion.shareParser
import com.opensource.svgaplayer.SVGAParser.ParseCompletion
import com.opensource.svgaplayer.SVGASoundManager.init
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.utils.log.SVGALogger.setLogEnabled


class AnimationFromAssetsActivity : Activity() {
    var currentIndex = 0
    var animationView: SVGAImageView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animationView = SVGAImageView(this)
        animationView!!.clearsAfterDetached = true
        animationView!!.setBackgroundColor(Color.BLACK)
        animationView!!.setOnClickListener { animationView!!.stepToFrame(currentIndex++, false) }
        setLogEnabled(true)
        init()
        loadAnimation()
        setContentView(animationView)
        animationView?.setOnClickListener {
            loadAnimation()
        }
    }

    private fun loadAnimation() {
        val svgaParser = shareParser()
        val name = "mp3_to_long.svga"
        Log.d("zzzz", "## name $name")
        svgaParser.setFrameSize(150, 150)
        svgaParser.decodeFromAssets(name, object : ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                Log.e("zzzz", "onComplete: ")
                animationView!!.setVideoItem(videoItem)
                animationView!!.stepToFrame(0, true)
            }

            override fun onError() {
                Log.e("zzzz", "onComplete: ")
            }
        }, null)
    }

    private val samples: ArrayList<String?> = ArrayList()
    private fun randomSample(): String? {
        if (samples.size == 0) {
            samples.add("750x80.svga")
            samples.add("alarm.svga")
            samples.add("angel.svga")
            samples.add("Castle.svga")
            samples.add("EmptyState.svga")
            samples.add("Goddess.svga")
            samples.add("gradientBorder.svga")
            samples.add("heartbeat.svga")
            samples.add("matteBitmap.svga")
            samples.add("matteBitmap_1.x.svga")
            samples.add("matteRect.svga")
            samples.add("MerryChristmas.svga")
            samples.add("posche.svga")
            samples.add("Rocket.svga")
            samples.add("rose.svga")
            samples.add("rose_2.0.0.svga")
        }
        return samples[Math.floor(Math.random() * samples.size).toInt()]
    }
}