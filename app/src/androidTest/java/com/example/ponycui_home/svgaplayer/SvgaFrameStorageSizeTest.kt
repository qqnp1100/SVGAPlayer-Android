package com.example.ponycui_home.svgaplayer

import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import com.opensource.svgaplayer.loader.SvgaSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SvgaFrameStorageSizeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun movie(frames: List<JSONObject>) = JSONObject()
        .put("movie", JSONObject().put("fps", 30).put("frames", frames.size)
            .put("viewBox", JSONObject().put("width", 64).put("height", 64)))
        .put("sprites", JSONArray().put(JSONObject().put("imageKey", "image")
            .put("frames", JSONArray(frames))))

    private fun resource(spec: JSONObject): SvgaResource = SvgaResource::class.java
        .getDeclaredConstructor(SVGAVideoEntity::class.java).apply { isAccessible = true }
        .newInstance(SVGAVideoEntity(spec, context.cacheDir))

    @Test fun thousandRepeatedJsonMasksIncludeRetainedTextInResourceSize() {
        val mask = "M 0 0 L 1 1 Z ".repeat(8192)
        val frame = JSONObject().put("alpha", 1).put("clipPath", mask)
        val resource = resource(movie(List(1000) { frame }))
        assertTrue("Resource estimate must include the retained mask text",
            resource.sizeBytes >= mask.length * 2L)
        val single = resource(movie(listOf(frame)))
        assertTrue("Shared mask payload must not be charged at every timeline index",
            resource.sizeBytes < single.sizeBytes * 2)
    }

    @Test fun shapeStylesAndDashArraysIncreaseResourceSize() {
        fun spec(dashes: Int) = movie(listOf(JSONObject().put("alpha", 1).put("shapes", JSONArray().put(
            JSONObject().put("type", "rect")
                .put("args", JSONObject().put("x", 0).put("y", 0).put("width", 64)
                    .put("height", 64).put("cornerRadius", 0))
                .put("styles", JSONObject().put("lineDash", JSONArray(List(dashes) { 1 }))
                    .put("lineCap", "round").put("lineJoin", "round"))
                .put("transform", JSONObject().put("a", 1).put("d", 1))))))
        assertTrue(resource(spec(4096)).sizeBytes - resource(spec(0)).sizeBytes >= 4096 * 4L)
    }

    @Test fun oversizedSharedMaskResourceDoesNotStayInStrongMemoryCache() = runBlocking {
        val mask = "M 0 0 L 1 1 Z ".repeat(512)
        val frame = JSONObject().put("alpha", 1).put("clipPath", mask)
        val file = File.createTempFile("frame-storage-size-", ".svga", context.cacheDir)
        val engine = SvgaEngine(context, memoryBytes = 4096, weakMemoryCacheEnabled = false)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec"))
                zip.write(movie(List(32) { frame }).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val request = SvgaRequest(SvgaSource.LocalFile(file), cachePolicy = SvgaCachePolicy.MEMORY,
                weakMemoryCache = false)
            val first = engine.acquire(request)
            assertNotSame("An oversized resource must be decoded again after LRU eviction",
                first, engine.acquire(request))
            assertEquals(2L, engine.decodeCount.get())
            assertTrue(first.sizeBytes >= mask.length * 2L)
        } finally {
            engine.close()
            file.delete()
        }
    }
}
