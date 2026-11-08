package com.example.ponycui_home.svgaplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.coil3.svgaBindings
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SvgaDynamicImageDecodeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun frame(width: Int, height: Int, a: Double, b: Double = 0.0, c: Double = 0.0,
        d: Double = a, alpha: Double = 1.0) = JSONObject().put("alpha", alpha)
        .put("layout", JSONObject().put("width", width).put("height", height))
        .put("transform", JSONObject().put("a", a).put("b", b).put("c", c).put("d", d))

    private fun fixture(): ByteArray {
        fun sprite(vararg frames: JSONObject) = JSONObject().put("imageKey", "avatar")
            .put("frames", JSONArray(frames.toList()))
        val spec = JSONObject().put("movie", JSONObject().put("viewBox",
            JSONObject().put("width", 400).put("height", 400)).put("fps", 20).put("frames", 2))
            .put("images", JSONObject()).put("sprites", JSONArray().apply {
                put(sprite(frame(120, 60, .5), frame(40, 20, 0.0, b = -2.0, c = 2.0, d = 0.0)))
                put(sprite(frame(10, 5, 1.0), frame(2000, 2000, 1.0, alpha = 0.0)))
            })
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec.toString().toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun source(directory: File, width: Int = 1024, height: Int = 512): File {
        val file = File(directory, "avatar-${width}x$height.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    @Test fun mismatchedSourceRatioCoversBothAxesAndExtremeRatiosStayBounded() = runBlocking {
        val directory = File(context.cacheDir, "dynamic-aspect-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val loader = ImageLoader.Builder(context).build()
        try {
            val resource = SvgaResource.decode(fixture().inputStream(), File(directory, "svga"))
            suspend fun decoded(width: Int, height: Int, size: Int = 0) =
                svgaBindings { image("avatar", source(directory, width, height), size = size) }
                    .prepare(loader, context, SvgaCachePolicy.NONE, resource, 400, 400)
            val square = decoded(1024, 1024)
            val wide = decoded(1024, 128)
            val extreme = decoded(4096, 1)
            val capped = decoded(1024, 128, size = 100)
            try {
                assertEquals(80, square.getDynamicImage("avatar")!!.width)
                assertEquals(80, square.getDynamicImage("avatar")!!.height)
                assertEquals(320, wide.getDynamicImage("avatar")!!.width)
                assertEquals(40, wide.getDynamicImage("avatar")!!.height)
                assertEquals(1024, extreme.getDynamicImage("avatar")!!.width)
                assertTrue(extreme.getDynamicImage("avatar")!!.height <= 1024)
                assertEquals(100, capped.getDynamicImage("avatar")!!.width)
            } finally {
                square.clearDynamicObjects(); wide.clearDynamicObjects()
                extreme.clearDynamicObjects(); capped.clearDynamicObjects()
            }
        } finally { loader.shutdown(); directory.deleteRecursively() }
    }

    @Test fun decodesForAllFrameUsesAndTheActualViewport() = runBlocking {
        val directory = File(context.cacheDir, "dynamic-size-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val loader = ImageLoader.Builder(context).build()
        try {
            val resource = SvgaResource.decode(fixture().inputStream(), File(directory, "svga"))
            assertEquals(android.util.Size(80, 40), resource.layerImageSize("avatar"))
            assertEquals(android.util.Size(40, 20), resource.layerImageSize("avatar", .5f, .5f))
            assertEquals(android.util.Size(60, 40), resource.layerImageSize("avatar", 1f, .5f))
            val bindings = svgaBindings { image("avatar", source(directory)) }
            val small = bindings.prepare(loader, context, SvgaCachePolicy.ALL, resource, 200, 200)
            val large = bindings.prepare(loader, context, SvgaCachePolicy.ALL, resource, 400, 400)
            val centered = bindings.prepare(loader, context, SvgaCachePolicy.ALL, resource, 200, 200, ImageView.ScaleType.CENTER)
            try {
                assertEquals(40, small.getDynamicImage("avatar")!!.width)
                assertEquals(20, small.getDynamicImage("avatar")!!.height)
                assertEquals(80, large.getDynamicImage("avatar")!!.width)
                assertEquals(40, large.getDynamicImage("avatar")!!.height)
                assertEquals(80, centered.getDynamicImage("avatar")!!.width)
                assertNotSame(small.getDynamicImage("avatar"), large.getDynamicImage("avatar"))
            } finally { small.clearDynamicObjects(); large.clearDynamicObjects(); centered.clearDynamicObjects() }
        } finally { loader.shutdown(); directory.deleteRecursively() }
    }

    @Test fun capsExplicitSizesAndCachesTheCircleCropResult() = runBlocking {
        val directory = File(context.cacheDir, "dynamic-circle-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val loader = ImageLoader.Builder(context).build()
        try {
            val resource = SvgaResource.decode(fixture().inputStream(), File(directory, "svga"))
            val file = source(directory)
            val bindings = svgaBindings { image("avatar", file, size = 8192, circleCrop = true) }
            val first = bindings.prepare(loader, context, SvgaCachePolicy.ALL, resource, 200, 200)
            val second = bindings.prepare(loader, context, SvgaCachePolicy.ALL, resource, 200, 200)
            val capped = svgaBindings { image("avatar", file, size = 10) }
                .prepare(loader, context, SvgaCachePolicy.NONE, resource, 200, 200)
            try {
                val bitmap = first.getDynamicImage("avatar")!!
                assertEquals(40, bitmap.width); assertEquals(40, bitmap.height)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals(Color.RED, bitmap.getPixel(20, 20))
                assertSame(bitmap, second.getDynamicImage("avatar"))
                first.clearDynamicObjects()
                assertFalse(bitmap.isRecycled)
                assertEquals(10, capped.getDynamicImage("avatar")!!.width)
                assertEquals(5, capped.getDynamicImage("avatar")!!.height)
            } finally { first.clearDynamicObjects(); second.clearDynamicObjects(); capped.clearDynamicObjects() }
        } finally { loader.shutdown(); directory.deleteRecursively() }
    }
}
