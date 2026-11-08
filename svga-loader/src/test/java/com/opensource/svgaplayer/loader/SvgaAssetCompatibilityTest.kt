package com.opensource.svgaplayer.loader

import android.graphics.Bitmap
import android.graphics.Canvas
import com.opensource.svgaplayer.SvgaDecodeOptions
import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.InflaterInputStream

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgaAssetCompatibilityTest(private val name: String, private val progressive: Boolean, private val inBitmap: Boolean) {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bundledAssetDecodesAndPreparesFirstFrame() = runBlocking {
        val directory = temporary.newFolder()
        val resource = requireNotNull(javaClass.getResourceAsStream("/$name")).use {
            SvgaResource.decode(it, directory, 256, 256,
                decodeOptions = SvgaDecodeOptions(progressive = progressive, inBitmap = inBitmap))
        }
        // zlib exports embed their images/audio in the same MovieEntity used by ZIP movie.binary.
        if (!File(directory, "movie.binary").isFile && !File(directory, "movie.spec").isFile) {
            InflaterInputStream(requireNotNull(javaClass.getResourceAsStream("/$name"))).use { input ->
                File(directory, "movie.binary").outputStream().use { input.copyTo(it) }
            }
        }
        val cached = SvgaResource.decodeFromDirectory(directory, 256, 256,
            decodeOptions = SvgaDecodeOptions(progressive = progressive, inBitmap = inBitmap))
        assertEquals(resource.frames, cached.frames); assertEquals(resource.fps, cached.fps)
        assertTrue(directory.deleteRecursively())
        assertTrue(resource.frames > 0)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            resource.newRenderer().use { renderer ->
                renderer.prepare(256, 256)
                renderer.draw(Canvas(bitmap), 256, 256, 0, 0)
            }
            cached.newRenderer().use { renderer ->
                renderer.prepare(256, 256)
                renderer.draw(Canvas(actual), 256, 256, 0, 0)
            }
            val expectedPixels = IntArray(256 * 256); val actualPixels = IntArray(256 * 256)
            bitmap.getPixels(expectedPixels, 0, 256, 0, 0, 256, 256)
            actual.getPixels(actualPixels, 0, 256, 0, 0, 256, 256)
            assertArrayEquals(expectedPixels, actualPixels)
            assertFalse(resource.hasImageDecodeError)
            assertFalse(cached.hasImageDecodeError)
        } finally { bitmap.recycle(); actual.recycle() }
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}, progressive={1}, inBitmap={2}") fun cases(): List<Array<Any>> =
            listOf("750x80.svga", "Castle.svga", "EmptyState.svga", "Goddess.svga", "MerryChristmas.svga",
                "Rocket.svga", "alarm.svga", "angel.svga", "gradientBorder.svga", "heartbeat.svga",
                "jojo_audio.svga", "matteBitmap.svga", "matteBitmap_1.x.svga", "matteRect.svga",
                "mp3_to_long.svga", "posche.svga", "rose.svga", "rose_2.0.0.svga").flatMap { name ->
                listOf(false, true).flatMap { progressive ->
                    listOf(false, true).map { inBitmap -> arrayOf<Any>(name, progressive, inBitmap) }
                }
            }
    }
}
