package com.opensource.svgaplayer.glide5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SvgaDecodeOptions
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaDownloadProgress
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import com.opensource.svgaplayer.loader.SvgaSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgaProgressiveRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(width: Int = 1000, height: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val frame = JSONObject().put("alpha", 1)
            .put("layout", JSONObject().put("width", width).put("height", height))
            .put("transform", JSONObject().put("a", 1).put("d", 1))
        val spec = JSONObject().put("movie", JSONObject()
            .put("viewBox", JSONObject().put("width", 1000).put("height", 1000))
            .put("fps", 20).put("frames", 2))
            .put("images", JSONObject().put("banner", "banner.png"))
            .put("sprites", JSONArray().put(JSONObject().put("imageKey", "banner")
                .put("frames", JSONArray().put(frame).put(frame))))
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("movie.spec")); zip.write(spec.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("banner.png")); zip.write(png); zip.closeEntry()
        } }.toByteArray()
    }

    private fun drawable(resource: SvgaResource) = SVGADrawable(resource.newVideoEntity()).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        showStaticFrame()
    }

    private fun <T> withBitmapFactory(block: () -> T): T {
        // Initialize native graphics before selecting the pre-28 production decoder branch.
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).recycle()
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
        return try { block() }
        finally { ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk) }
    }

    @Test fun bitmapFactoryScaledSharedImagesFitAStableBudgetAndMatchEagerFrames() = withBitmapFactory { runBlocking {
        for ((width, height) in listOf(1000 to 100, 100 to 1000, 1001 to 101, 65 to 63)) {
            val bytes = fixture(width, height)
            val eager = drawable(SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), 128, 64))
            val resource = SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), 128, 64,
                decodeOptions = SvgaDecodeOptions(progressive = true))
            val progressive = drawable(resource)
            val weight = resource.sizeBytes
            val actual = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            val expected = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            try {
                if (width == 1000 && height == 100) {
                    // Requested 128x13 pixels become 130x13 through native density scaling.
                    assertEquals(130, eager.videoItem.getImageSizeByKey("banner")!!.width)
                }
                progressive.prepareFrame(0)
                assertTrue(progressive.isFrameReady(0))
                eager.draw(Canvas(expected)); progressive.draw(Canvas(actual))
                assertTrue("Scaled frame differs for ${width}x$height", expected.sameAs(actual))
                assertEquals("Background decoding must not change cache weight", weight, resource.sizeBytes)
                assertTrue(resource.sizeBytes >= progressive.videoItem.imageMapSize())
                assertFalse(resource.hasImageDecodeError)
            } finally { progressive.clear(); eager.clear(); actual.recycle(); expected.recycle() }
        }
    } }

    @Test fun roundedSharedPixelsMustFitTheResourceLimitBeforePublication() = withBitmapFactory { runBlocking {
        val bytes = fixture()
        val tooSmall = runCatching {
            SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), 128, 64,
                maxDecodedBytes = 128L * 13 * 4, decodeOptions = SvgaDecodeOptions(progressive = true))
        }.exceptionOrNull()
        assertTrue("The resource must reserve the rounded pixels before entering caches", tooSmall is IllegalArgumentException)
        val resource = SvgaResource.decode(bytes.inputStream(), temporary.newFolder(), 128, 64,
            maxDecodedBytes = 130L * 13 * 4, decodeOptions = SvgaDecodeOptions(progressive = true))
        val presentation = drawable(resource)
        try { presentation.prepareFrame(0); assertTrue(presentation.isFrameReady(0)) }
        finally { presentation.clear() }
    } }

    // Inspect library-owned references without depending on nondeterministic GC timing.
    private fun retains(root: Any, target: Any): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?): Boolean {
            if (value === target) return true
            if (value == null || !seen.add(value) || !value.javaClass.name.startsWith("com.opensource.svgaplayer.")) return false
            return generateSequence(value.javaClass as Class<*>?) { it.superclass }
                .filter { it.name.startsWith("com.opensource.svgaplayer.") }.any { type ->
                type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }.any {
                    it.isAccessible = true
                    visit(it.get(value))
                }
            }
        }
        return visit(root)
    }

    @Test fun cachedResourceDoesNotRetainTheRequestProgressCallback() = runBlocking {
        val file = temporary.newFile("gift.svga").apply { writeBytes(fixture()) }
        val owner = Any()
        val callback: suspend (SvgaDownloadProgress) -> Unit = { owner.hashCode(); Unit }
        SvgaEngine(RuntimeEnvironment.getApplication()).use { engine ->
            val request = SvgaRequest(SvgaSource.LocalFile(file), progressive = true, onDownloadProgress = callback)
            val resource = engine.acquire(request)
            assertSame(resource, engine.acquire(request.copy(onDownloadProgress = null)))
            assertFalse("Cached resources must release request progress callbacks", retains(resource, callback))
            assertFalse("Callback owners must not be retained by cached resources", retains(resource, owner))
        }
    }
}
