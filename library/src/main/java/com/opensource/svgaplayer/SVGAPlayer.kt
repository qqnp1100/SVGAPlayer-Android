package com.opensource.svgaplayer

import android.content.Context
import android.util.AttributeSet

/**
 * Created by cuiminghui on 2017/3/30.
 * @deprecated from 2.4.0
 */
@Deprecated("Deprecated since 2.4.0. Use SVGAImageView with loadSvga, or the Compose SvgaView component.")
class SVGAPlayer: SVGAImageView {

    constructor(context: Context) : super(context) {}

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {}

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {}

}
