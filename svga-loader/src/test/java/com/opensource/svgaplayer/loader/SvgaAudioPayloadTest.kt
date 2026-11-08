package com.opensource.svgaplayer.loader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.opensource.svgaplayer.SvgaDecodeOptions
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.proto.*
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgaAudioPayloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val audio = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0)

    private fun fixture(payload: ByteArray = audio, declaredAudio: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        fun frame(alpha: Float) = FrameEntity(alpha, Layout(0f, 0f, 16f, 16f),
            Transform(1f, 0f, 0f, 1f, 0f, 0f), null, emptyList())
        val images = linkedMapOf("base" to png.toByteString(), "orphan" to payload.toByteString())
        if (declaredAudio) images["sound"] = audio.toByteString()
        val movie = MovieEntity("2.0", MovieParams(16f, 16f, 20, 2), images,
            listOf(SpriteEntity("base", listOf(frame(1f), frame(0f)), null),
                SpriteEntity("orphan", listOf(frame(1f), frame(1f)), null)),
            if (declaredAudio) listOf(AudioEntity("sound", 0, 2, 7, 100)) else emptyList())
        return ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(MovieEntity.ADAPTER.encode(movie)) }
        }.toByteArray()
    }

    @Test fun undeclaredId3AudioIsSkippedAndVisibleImagesStillRenderInAllModes() = runBlocking {
        val bytes = fixture()
        for (progressive in listOf(false, true)) for (inBitmap in listOf(false, true)) {
            val resource = SvgaResource.decode(bytes.inputStream(), temporary.newFolder(),
                decodeOptions = SvgaDecodeOptions(progressive = progressive, inBitmap = inBitmap))
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                resource.newRenderer().use { renderer ->
                    renderer.prepare(16, 16)
                    renderer.draw(Canvas(bitmap), 16, 16, 0, 0)
                }
                assertEquals(Color.RED, bitmap.getPixel(8, 8))
                assertFalse(resource.hasImageDecodeError)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun declaredAudioIsRetainedWhenUndeclaredAudioIsSkipped() = runBlocking {
        val resource = SvgaResource.decode(fixture(declaredAudio = true).inputStream(), temporary.newFolder())
        val tracks = ReflectionHelpers.getField<List<Any>>(resource, "audioTracks")
        assertEquals(1, tracks.size)
        assertArrayEquals(audio, ReflectionHelpers.getField<ByteArray>(tracks.single(), "bytes"))
        assertEquals(7, ReflectionHelpers.getField<Int>(tracks.single(), "startTimeMillis"))
    }

    @Test fun corruptedImageStillFailsInsteadOfBeingSilentlySkipped() = runBlocking {
        val bytes = fixture(payload = ByteArray(4096))
        for (progressive in listOf(false, true)) for (inBitmap in listOf(false, true)) {
            val error = runCatching {
                SvgaResource.decode(bytes.inputStream(), temporary.newFolder(),
                    decodeOptions = SvgaDecodeOptions(progressive = progressive, inBitmap = inBitmap))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("Invalid image orphan", error?.message)
        }
    }

    @Test fun invalidId3PrefixIsNotEnoughToSkipAnImage() = runBlocking {
        val bytes = fixture(payload = "ID3not-an-audio-header".toByteArray() + ByteArray(4096))
        val error = runCatching { SvgaResource.decode(bytes.inputStream(), temporary.newFolder()) }.exceptionOrNull()
        assertEquals("Invalid image orphan", error?.message)
    }
}
