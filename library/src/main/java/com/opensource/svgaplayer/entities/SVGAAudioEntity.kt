package com.opensource.svgaplayer.entities

import com.opensource.svgaplayer.proto.AudioEntity

internal class SVGAAudioEntity {

    val audioKey: String?
    val startFrame: Int
    val endFrame: Int
    val startTime: Int
    val totalTime: Int
    var soundID: Int? = null
        set(value) {
            field = value
            playIDs = HashSet()
        }
    var playIDs: HashSet<Int>? = null

    constructor(audioItem: AudioEntity) {
        this.audioKey = audioItem.audioKey
        this.startFrame = audioItem.startFrame ?: 0
        this.endFrame = audioItem.endFrame ?: 0
        this.startTime = audioItem.startTime ?: 0
        this.totalTime = audioItem.totalTime ?: 0
    }

}