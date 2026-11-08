package com.opensource.svgaplayer.drawer

internal class FrameChangeGate {
    private var lastFrame: Int? = null

    fun shouldProcess(frame: Int): Boolean {
        if (lastFrame == frame) return false
        lastFrame = frame
        return true
    }

    fun clear() {
        lastFrame = null
    }
}
