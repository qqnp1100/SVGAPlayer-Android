package com.opensource.svgaplayer.entities

import com.opensource.svgaplayer.proto.SpriteEntity
import org.json.JSONObject

internal class SVGAVideoSpriteEntity {
    val imageKey: String?
    val matteKey: String?
    /** One reference per original frame index, including empty and duplicate frames. */
    val frames: List<SVGAVideoSpriteFrameEntity>

    constructor(obj: JSONObject, pool: SvgaFramePool) {
        imageKey = obj.optString("imageKey")
        matteKey = obj.optString("matteKey")
        val array = obj.optJSONArray("frames")
        val indexed = ArrayList<SVGAVideoSpriteFrameEntity>(array?.length() ?: 0)
        if (array != null) for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { indexed.add(pool.frame(it, indexed.lastOrNull())) }
        }
        frames = indexed
    }

    constructor(obj: SpriteEntity, pool: SvgaFramePool) {
        imageKey = obj.imageKey
        matteKey = obj.matteKey
        val indexed = ArrayList<SVGAVideoSpriteFrameEntity>(obj.frames.size)
        obj.frames.forEach { indexed.add(pool.frame(it, indexed.lastOrNull())) }
        frames = indexed
    }

    fun clear() {
        frames.toSet().forEach { it.clear() }
    }
}
