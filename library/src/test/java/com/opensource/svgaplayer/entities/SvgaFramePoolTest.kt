package com.opensource.svgaplayer.entities

import com.opensource.svgaplayer.proto.FrameEntity
import com.opensource.svgaplayer.proto.Layout
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.ShapeEntity
import com.opensource.svgaplayer.proto.SpriteEntity
import com.opensource.svgaplayer.proto.Transform
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.InflaterInputStream

class SvgaFramePoolTest {
    private fun empty() = FrameEntity.Builder().build()
    private fun visible(alpha: Float = 1f) = FrameEntity.Builder().alpha(alpha)
        .layout(Layout(0f, 0f, 40f, 20f)).transform(Transform(0f, -2f, 3f, 0f, 10f, 15f)).build()
    private fun shaped(width: Float) = FrameEntity.Builder().alpha(1f).shapes(listOf(
        ShapeEntity.Builder().type(ShapeEntity.ShapeType.RECT)
            .rect(ShapeEntity.RectArgs.Builder().width(width).height(10f).build()).build()
    )).build()

    @Test fun sparseTimelineRetainsAllIndicesIncludingLeadingTrailingAndMiddleEmptyFrames() {
        val sources = MutableList(10000) { empty() }
        sources[17] = visible(); sources[9998] = visible(.5f)
        val sprite = SVGAVideoSpriteEntity(SpriteEntity("image", sources, null), SvgaFramePool())
        assertEquals(10000, sprite.frames.size)
        assertEquals(0.0, sprite.frames[0].alpha, 0.0)
        assertEquals(1.0, sprite.frames[17].alpha, 0.0)
        assertEquals(.5, sprite.frames[9998].alpha, 0.0)
        assertSame(sprite.frames[0], sprite.frames[9999])
        assertSame(sprite.frames[0], sprite.frames[18])
        assertNull(sprite.frames.getOrNull(10000))
    }

    @Test fun equalSourceFramesShareStorageAcrossLayersWithoutChangingEachTimeline() {
        val pool = SvgaFramePool()
        val first = SVGAVideoSpriteEntity(SpriteEntity("a", listOf(empty(), visible(), visible()), null), pool)
        val second = SVGAVideoSpriteEntity(SpriteEntity("b", listOf(visible(), empty()), "mask"), pool)
        assertSame(first.frames[1], first.frames[2])
        assertSame(first.frames[1], second.frames[0])
        assertSame(first.frames[0], second.frames[1])
        assertEquals("b", second.imageKey); assertEquals("mask", second.matteKey)
        assertEquals(listOf(0.0, 1.0, 1.0), first.frames.map { it.alpha })
    }

    @Test fun differentAlphaFramesCanShareImmutableLayoutAndTransform() {
        val pool = SvgaFramePool()
        val first = pool.frame(visible(), null)
        val second = pool.frame(visible(.5f), null)
        assertNotSame(first, second)
        assertSame(first.layout, second.layout)
        assertSame(first.transform, second.transform)
        val values = FloatArray(9)
        first.transform.getValues(values)
        assertArrayEquals(floatArrayOf(0f, 3f, 10f, -2f, 0f, 15f, 0f, 0f, 1f), values, 0f)
    }

    @Test fun keepFramesAreResolvedAgainstTheirOwnPredecessorIncludingKeepChains() {
        val pool = SvgaFramePool()
        val source = FrameEntity.Builder().alpha(1f).shapes(listOf(
            ShapeEntity.Builder().type(ShapeEntity.ShapeType.KEEP).build())).build()
        val first = pool.frame(shaped(10f), null)
        val second = pool.frame(shaped(20f), null)
        val keptFirst = pool.frame(source, first)
        val keptSecond = pool.frame(source, second)
        assertSame(first.shapes, keptFirst.shapes)
        assertSame(second.shapes, keptSecond.shapes)
        assertNotSame(keptFirst, keptSecond)
        assertSame(keptFirst, pool.frame(source, keptFirst))
        val firstFrameKeep = pool.frame(source, null)
        assertTrue(firstFrameKeep.shapes.first().isKeep)
        val afterEmpty = pool.frame(source, pool.frame(empty(), null))
        assertTrue(afterEmpty.shapes.isEmpty())
        assertNotSame(firstFrameKeep, afterEmpty)
    }

    @Test fun emptyDefaultsAndSignedZeroCoefficientsRetainTheirExactValues() {
        val pool = SvgaFramePool()
        val frame = pool.frame(empty(), null)
        assertEquals(0.0, frame.layout.width, 0.0)
        assertNull(frame.maskPath); assertTrue(frame.shapes.isEmpty())
        val identity = FloatArray(9); frame.transform.getValues(identity)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), identity, 0f)
        val signed = pool.transform(1f, -0f, 0f, 1f, -0f, 0f)
        assertNotSame(frame.transform, signed)
        val values = FloatArray(9); signed.getValues(values)
        assertEquals((-0f).toRawBits(), values[3].toRawBits())
        assertEquals((-0f).toRawBits(), values[2].toRawBits())
    }

    @Test fun clearingOneTimelineDoesNotDamageGlobalEmptyDefaults() {
        val first = SVGAVideoSpriteEntity(SpriteEntity("a", listOf(empty(), empty()), null), SvgaFramePool())
        val second = SVGAVideoSpriteEntity(SpriteEntity("b", listOf(empty()), null), SvgaFramePool())
        first.clear()
        assertEquals(0.0, second.frames[0].alpha, 0.0)
        assertSame(SvgaFrameTransform.IDENTITY, second.frames[0].transform)
        assertTrue(second.frames[0].shapes.isEmpty())
    }

    @Test fun repeatedFramesAccountForMaskTextWithoutMultiplyingTheSharedPayload() {
        val shortPath = "M 0 0 L 1 1 Z "
        val longPath = shortPath.repeat(8192)
        fun estimate(path: String, count: Int): Long {
            val pool = SvgaFramePool()
            val source = FrameEntity.Builder().alpha(1f).clipPath(path).build()
            repeat(count) { pool.frame(source, null) }
            return pool.estimatedBytes(count.toLong())
        }
        assertTrue(estimate(longPath, 1000) >= longPath.length * 2L)
        assertEquals(estimate(longPath, 1) - estimate(shortPath, 1),
            estimate(longPath, 1000) - estimate(shortPath, 1000))
    }

    @Test fun keepFramesAccountForTheirSharedVectorPayloadOnlyOnce() {
        fun estimate(path: String, keepCount: Int): Long {
            val pool = SvgaFramePool()
            var previous = pool.frame(FrameEntity.Builder().alpha(1f).shapes(listOf(
                ShapeEntity.Builder().type(ShapeEntity.ShapeType.SHAPE)
                    .shape(ShapeEntity.ShapeArgs.Builder().d(path).build()).build())).build(), null)
            repeat(keepCount) { index ->
                previous = pool.frame(FrameEntity.Builder().alpha((index + 1) / 1000f).shapes(listOf(
                    ShapeEntity.Builder().type(ShapeEntity.ShapeType.KEEP).build())).build(), previous)
            }
            return pool.estimatedBytes(keepCount + 1L)
        }
        val shortPath = "M 0 0 L 1 1 Z "
        val longPath = shortPath.repeat(8192)
        assertTrue(estimate(longPath, 0) >= longPath.length * 2L)
        assertEquals(estimate(longPath, 0) - estimate(shortPath, 0),
            estimate(longPath, 100) - estimate(shortPath, 100))
    }

    @Test fun equalButSeparatelyRetainedMaskStringsAreCountedIndividually() {
        val path = "M 0 0 L 1 1 Z ".repeat(8192)
        fun estimate(secondPath: String): Long {
            val pool = SvgaFramePool()
            pool.frame(FrameEntity.Builder().alpha(1f).clipPath(path).build(), null)
            pool.frame(FrameEntity.Builder().alpha(.5f).clipPath(secondPath).build(), null)
            return pool.estimatedBytes(2)
        }
        val copy = String(path.toCharArray())
        assertNotSame(path, copy)
        assertTrue(estimate(copy) - estimate(path) >= path.length * 2L)
    }

    @Test fun nativePathAllowanceGrowsWithGeometryEvenWhenTextLengthIsUnchanged() {
        val command = "M 0 0 L 1 1 Z "
        val dense = command.repeat(1024)
        val sparse = command.padEnd(dense.length, ' ')
        fun estimate(path: String): Long {
            val pool = SvgaFramePool()
            pool.frame(FrameEntity.Builder().alpha(1f).clipPath(path).build(), null)
            return pool.estimatedBytes(1)
        }
        assertTrue("Native geometry needs space in addition to the retained string",
            estimate(dense) > estimate(sparse))
    }

    @Test fun existingBitmapOnlyProtobufSamplesPreserveEveryIndexedValueAndReduceObjects() {
        val assets = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val reports = ArrayList<String>()
        for (name in listOf("Castle.svga", "MerryChristmas.svga", "angel.svga", "heartbeat.svga",
            "jojo_audio.svga", "rose.svga", "rose_2.0.0.svga")) {
            val movie = File(assets, name).inputStream().use { input ->
                InflaterInputStream(input).use { MovieEntity.ADAPTER.decode(it) }
            }
            val pool = SvgaFramePool()
            val sprites = movie.sprites.map { SVGAVideoSpriteEntity(it, pool) }
            val uniqueFrames = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SVGAVideoSpriteFrameEntity, Boolean>())
            val uniqueTransforms = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SvgaFrameTransform, Boolean>())
            var records = 0
            for ((layerIndex, sprite) in sprites.withIndex()) {
                val source = movie.sprites[layerIndex]
                assertEquals(source.frames.size, sprite.frames.size)
                for ((index, frame) in sprite.frames.withIndex()) {
                    val original = source.frames[index]
                    assertTrue("Host fixture contains native shape data: $name", original.shapes.isEmpty())
                    assertEquals("$name layer=$layerIndex frame=$index", (original.alpha ?: 0f).toDouble(), frame.alpha, 0.0)
                    val layout = original.layout
                    assertEquals((layout?.x ?: 0f).toDouble(), frame.layout.x, 0.0)
                    assertEquals((layout?.y ?: 0f).toDouble(), frame.layout.y, 0.0)
                    assertEquals((layout?.width ?: 0f).toDouble(), frame.layout.width, 0.0)
                    assertEquals((layout?.height ?: 0f).toDouble(), frame.layout.height, 0.0)
                    val transform = original.transform
                    val expected = floatArrayOf(transform?.a ?: 1f, transform?.c ?: 0f, transform?.tx ?: 0f,
                        transform?.b ?: 0f, transform?.d ?: 1f, transform?.ty ?: 0f, 0f, 0f, 1f)
                    val actual = FloatArray(9); frame.transform.getValues(actual)
                    expected.indices.forEach { assertEquals(expected[it].toRawBits(), actual[it].toRawBits()) }
                    assertEquals(original.clipPath.isNullOrEmpty(), frame.maskPath == null)
                    uniqueFrames.add(frame); uniqueTransforms.add(frame.transform); records++
                }
            }
            assertTrue("No duplicate compaction for $name", uniqueFrames.size < records)
            reports.add("""{"asset":"$name","frameRecords":$records,"uniqueFrames":${uniqueFrames.size},"uniqueTransforms":${uniqueTransforms.size},"retainedFrameMatrices":0,"frameStorageEstimateBytes":${pool.estimatedBytes(records.toLong())}}""")
        }
        File("build/reports/frame-storage/protobuf-structure.json").apply {
            checkNotNull(parentFile).mkdirs(); writeText(reports.joinToString(",\n", "[\n", "\n]\n"))
        }
    }
}
