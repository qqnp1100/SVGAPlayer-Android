package com.opensource.svgaplayer.entities

import com.opensource.svgaplayer.proto.FrameEntity
import com.opensource.svgaplayer.utils.SVGARect
import org.json.JSONObject

/** Scalar frame data is immutable and can be shared at multiple timeline indices. */
internal class SVGAVideoSpriteFrameEntity private constructor(
    val alpha: Double,
    val layout: SVGARect,
    val transform: SvgaFrameTransform,
    val maskPath: SVGAPathEntity?,
    shapes: List<SVGAVideoShapeEntity>,
) {
    var shapes: List<SVGAVideoShapeEntity> = shapes
        private set

    companion object {
        val EMPTY = SVGAVideoSpriteFrameEntity(0.0, SVGARect(0.0, 0.0, 0.0, 0.0),
            SvgaFrameTransform.IDENTITY, null, emptyList())

        fun from(obj: JSONObject, pool: SvgaFramePool, inheritedShapes: List<SVGAVideoShapeEntity>?): SVGAVideoSpriteFrameEntity {
            val layout = obj.optJSONObject("layout")
            val transform = obj.optJSONObject("transform")
            val shapes = inheritedShapes ?: obj.optJSONArray("shapes")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) array.optJSONObject(index)?.let { add(SVGAVideoShapeEntity(it)) }
                }
            }.orEmpty()
            return SVGAVideoSpriteFrameEntity(obj.optDouble("alpha", 0.0),
                pool.layout(layout?.optDouble("x", 0.0) ?: 0.0, layout?.optDouble("y", 0.0) ?: 0.0,
                    layout?.optDouble("width", 0.0) ?: 0.0, layout?.optDouble("height", 0.0) ?: 0.0),
                pool.transform((transform?.optDouble("a", 1.0) ?: 1.0).toFloat(),
                    (transform?.optDouble("b", 0.0) ?: 0.0).toFloat(), (transform?.optDouble("c", 0.0) ?: 0.0).toFloat(),
                    (transform?.optDouble("d", 1.0) ?: 1.0).toFloat(), (transform?.optDouble("tx", 0.0) ?: 0.0).toFloat(),
                    (transform?.optDouble("ty", 0.0) ?: 0.0).toFloat()),
                obj.optString("clipPath").takeIf { it.isNotEmpty() }?.let(::SVGAPathEntity), shapes)
        }

        fun from(obj: FrameEntity, pool: SvgaFramePool, inheritedShapes: List<SVGAVideoShapeEntity>?): SVGAVideoSpriteFrameEntity {
            val layout = obj.layout
            val transform = obj.transform
            return SVGAVideoSpriteFrameEntity((obj.alpha ?: 0f).toDouble(),
                pool.layout((layout?.x ?: 0f).toDouble(), (layout?.y ?: 0f).toDouble(),
                    (layout?.width ?: 0f).toDouble(), (layout?.height ?: 0f).toDouble()),
                pool.transform(transform?.a ?: 1f, transform?.b ?: 0f, transform?.c ?: 0f,
                    transform?.d ?: 1f, transform?.tx ?: 0f, transform?.ty ?: 0f),
                obj.clipPath?.takeIf { it.isNotEmpty() }?.let(::SVGAPathEntity),
                inheritedShapes ?: obj.shapes.map(::SVGAVideoShapeEntity))
        }
    }

    fun clear() {
        shapes.forEach { it.clear() }
        shapes = emptyList()
    }
}
