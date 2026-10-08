package com.opensource.svgaplayer

/**
 * Created by miaojun on 2019/6/21.
 * mail:1290846731@qq.com
 */
@Deprecated("Deprecated since 3.0.0. Retained for legacy View click areas; Compose supports SvgaView.onLayerClick.")
interface SVGAClickAreaListener{
    fun onClick(clickKey : String)
}
