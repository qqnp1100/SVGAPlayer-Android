package com.opensource.svgaplayer.entities

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/** Parsing-local accounting for variable-size data retained by interned frames. */
internal class SvgaFrameSizeEstimator {
    private val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun payloadBytes(frame: SVGAVideoSpriteFrameEntity): Long =
        bytes(frame.maskPath) + bytes(frame.shapes)

    private fun bytes(value: Any?): Long {
        if (value == null || value === JSONObject.NULL || !seen.add(value)) return 0
        return when (value) {
            is SVGAPathEntity -> 32L + bytes(value.pathData) + pathBytes(value.pathData)
            is SVGAVideoShapeEntity -> 64L + bytes(value.args) + bytes(value.styles) + bytes(value.transform) +
                // Reserve the resource's cached native Path even before buildPath() runs.
                ((value.args?.get("d") as? String)?.let(::pathBytes) ?: 512L)
            is SVGAVideoShapeEntity.Styles -> 64L + bytes(value.lineCap) + bytes(value.lineJoin) + bytes(value.lineDash)
            is Matrix -> 128L // Java wrapper plus native affine matrix.
            is String -> 40L + value.length * 2L // Allow for UTF-16 even on runtimes with compact strings.
            is FloatArray -> 24L + value.size * 4L
            is Map<*, *> -> 64L + value.size * 48L + value.entries.sumOf { bytes(it.key) + bytes(it.value) }
            is List<*> -> 32L + value.size * 8L + value.sumOf { bytes(it) }
            // JSON shape arguments may retain nested values from the source document.
            is JSONObject -> {
                var total = 96L + value.length() * 48L
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    total += bytes(key) + bytes(value.opt(key))
                }
                total
            }
            is JSONArray -> {
                var total = 48L + value.length() * 8L
                for (index in 0 until value.length()) total += bytes(value.opt(index))
                total
            }
            else -> 32L // Boxed scalar shape arguments.
        }
    }

    private fun pathBytes(data: String): Long {
        // Path has no allocationByteCount API. Allow native storage and capacity slack for
        // up to three control points per SVG command; this is an estimate, not a heap measurement.
        val commands = data.count { it in "MLHVCSQRAZmlhvcsqraz" }
        return 256L + commands * 64L
    }
}
