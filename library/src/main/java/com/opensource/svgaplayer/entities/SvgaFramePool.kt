package com.opensource.svgaplayer.entities

import com.opensource.svgaplayer.proto.FrameEntity
import com.opensource.svgaplayer.proto.ShapeEntity
import com.opensource.svgaplayer.utils.SVGARect
import org.json.JSONArray
import org.json.JSONObject

/** Parsing-local interning: source messages and JSON keys are released after the movie is built. */
internal class SvgaFramePool {
    private data class LayoutKey(val x: Double, val y: Double, val width: Double, val height: Double)
    private data class KeepKey(val source: FrameEntity, val inheritedShapes: List<SVGAVideoShapeEntity>?)
    private class JsonKey(val source: JSONObject, val inheritedShapes: List<SVGAVideoShapeEntity>?) {
        // Parsed JSON remains unchanged while the parsing-local pool is alive.
        // Referencing it avoids serializing every frame and retaining duplicate JSON strings.
        private val hash = jsonHash(source) * 31 + (inheritedShapes?.hashCode() ?: 0)
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean = other is JsonKey &&
            inheritedShapes == other.inheritedShapes && jsonEquals(source, other.source)
    }
    private val frames = HashMap<FrameEntity, SVGAVideoSpriteFrameEntity>()
    private val keepFrames = HashMap<KeepKey, SVGAVideoSpriteFrameEntity>()
    private val jsonFrames = HashMap<JsonKey, SVGAVideoSpriteFrameEntity>()
    private val layouts = hashMapOf(LayoutKey(0.0, 0.0, 0.0, 0.0) to SVGAVideoSpriteFrameEntity.EMPTY.layout)
    private val transforms = hashMapOf(SvgaFrameTransform.IDENTITY to SvgaFrameTransform.IDENTITY)
    private var usedEmpty = false

    fun frame(obj: FrameEntity, previous: SVGAVideoSpriteFrameEntity?): SVGAVideoSpriteFrameEntity {
        if (obj.alpha == null && obj.layout == null && obj.transform == null &&
            obj.clipPath.isNullOrEmpty() && obj.shapes.isEmpty()) {
            usedEmpty = true
            return SVGAVideoSpriteFrameEntity.EMPTY
        }
        if (obj.shapes.firstOrNull()?.type == ShapeEntity.ShapeType.KEEP) {
            // KEEP means the preceding frame's resolved shapes, not just the source message.
            val inherited = previous?.shapes
            return keepFrames.getOrPut(KeepKey(obj, inherited)) { SVGAVideoSpriteFrameEntity.from(obj, this, inherited) }
        }
        return frames.getOrPut(obj) { SVGAVideoSpriteFrameEntity.from(obj, this, null) }
    }

    fun frame(obj: JSONObject, previous: SVGAVideoSpriteFrameEntity?): SVGAVideoSpriteFrameEntity {
        if (obj.length() == 0) {
            usedEmpty = true
            return SVGAVideoSpriteFrameEntity.EMPTY
        }
        val shapes = obj.optJSONArray("shapes")
        val firstShape = shapes?.let { array -> (0 until array.length()).firstNotNullOfOrNull { array.optJSONObject(it) } }
        val keep = firstShape?.optString("type")?.equals("keep", true) == true
        val inherited = if (keep) previous?.shapes else null
        return jsonFrames.getOrPut(JsonKey(obj, inherited)) { SVGAVideoSpriteFrameEntity.from(obj, this, inherited) }
    }

    fun layout(x: Double, y: Double, width: Double, height: Double): SVGARect =
        layouts.getOrPut(LayoutKey(x, y, width, height)) { SVGARect(x, y, width, height) }

    fun transform(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): SvgaFrameTransform {
        val value = SvgaFrameTransform(a, b, c, d, tx, ty)
        return transforms.getOrPut(value) { value }
    }

    /** Cache estimate including shared payloads and native paths, even before paths are built. */
    fun estimatedBytes(indexCount: Long): Long {
        val estimator = SvgaFrameSizeEstimator()
        val payloadBytes = frames.values.sumOf { estimator.payloadBytes(it) } +
            keepFrames.values.sumOf { estimator.payloadBytes(it) } +
            jsonFrames.values.sumOf { estimator.payloadBytes(it) }
        return indexCount * 8 +
            (frames.size.toLong() + keepFrames.size + jsonFrames.size + if (usedEmpty) 1 else 0) * 256 +
            layouts.size * 48L + transforms.size * 40L + payloadBytes
    }

    companion object {
        private fun jsonHash(value: Any?): Int = when (value) {
            null, JSONObject.NULL -> 0
            is JSONObject -> {
                var hash = 0
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    hash += key.hashCode() xor jsonHash(value.opt(key))
                }
                hash
            }
            is JSONArray -> {
                var hash = 1
                for (index in 0 until value.length()) hash = hash * 31 + jsonHash(value.opt(index))
                hash
            }
            else -> value.hashCode()
        }

        private fun jsonEquals(first: Any?, second: Any?): Boolean {
            if (first === second) return true
            if (first == null || first === JSONObject.NULL) return second == null || second === JSONObject.NULL
            if (second == null || second === JSONObject.NULL) return false
            if (first is JSONObject && second is JSONObject) {
                if (first.length() != second.length()) return false
                val keys = first.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!second.has(key) || !jsonEquals(first.opt(key), second.opt(key))) return false
                }
                return true
            }
            if (first is JSONArray && second is JSONArray) {
                return first.length() == second.length() &&
                    (0 until first.length()).all { jsonEquals(first.opt(it), second.opt(it)) }
            }
            return first == second
        }
    }
}
