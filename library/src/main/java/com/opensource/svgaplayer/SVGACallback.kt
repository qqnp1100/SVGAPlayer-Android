package com.opensource.svgaplayer

/**
 * Created by cuiminghui on 2017/3/30.
 */
@Deprecated("Deprecated since 3.0.0. Use loadSvga/SvgaView callbacks and SvgaState for Compose playback state.")
interface SVGACallback {

    fun onPause()
    fun onFinished()
    fun onRepeat()
    fun onStep(frame: Int, percentage: Double)

}
