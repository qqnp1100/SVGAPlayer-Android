package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SvgaAudioSession
import com.opensource.svgaplayer.coil3.SvgaImageLoader
import com.opensource.svgaplayer.coil3.clearSvga
import com.opensource.svgaplayer.coil3.loadSvga
import com.opensource.svgaplayer.compose.SvgaLoadState
import com.opensource.svgaplayer.compose.SvgaState
import com.opensource.svgaplayer.compose.SvgaView
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SvgaZipCacheTest {
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private lateinit var engine: SvgaEngine
    private lateinit var loader: SvgaImageLoader

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "zip-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(target.applicationContext) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = directory
        }
        engine = SvgaEngine(context)
        loader = SvgaImageLoader(context, engine)
    }

    @After fun tearDown() {
        loader.close(); engine.close()
        directory.deleteRecursively()
    }

    private fun request(asset: String = "rose_2.0.0.svga"): SvgaRequest {
        val file = File(directory, "fixture.svga")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("movie.binary"))
            InflaterInputStream(context.assets.open(asset)).use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return SvgaRequest(file).copy(cachePolicy = SvgaCachePolicy.DISK)
    }

    private fun withTestActivity(block: (ComponentActivity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resumed = CountDownLatch(1)
        val activity = AtomicReference<ComponentActivity?>()
        val application = instrumentation.targetContext.applicationContext as Application
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(candidate: Activity) {
                if (candidate is ComponentActivity) { activity.set(candidate); resumed.countDown() }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callback) }
        try {
            // Start through the shell and observe the platform lifecycle; no Espresso/private input API is needed.
            val component = "${context.packageName}/${ComponentActivity::class.java.name}"
            val command = instrumentation.uiAutomation.executeShellCommand("am start -f 0x10008000 -n $component")
            val output = ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readText() }
            assertFalse(output, output.contains("Error:") || output.contains("Exception"))
            assertTrue("Test Activity did not resume", resumed.await(20, TimeUnit.SECONDS))
            block(checkNotNull(activity.get()))
        } finally {
            instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callback); activity.get()?.finish() }
        }
    }

    @Test fun recreatedEngineLoadsDirectoryAndReturnedLazyResourceRetainsImagesAndAudio() = runBlocking {
        val request = request("mp3_to_long.svga").copy(width = 128, height = 128, progressive = true, inBitmap = true)
        engine.acquire(request)
        assertEquals(1L, engine.zipExtractCount.get())
        loader.close(); engine.close()
        engine = SvgaEngine(context); loader = SvgaImageLoader(context, engine)
        val resource = loader.load(request)
        assertEquals(0L, engine.zipExtractCount.get()); assertEquals(1L, engine.zipCacheHits.get())
        engine.clearDisk()
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            resource.newRenderer().use { it.prepare(128, 128); it.draw(Canvas(bitmap), 128, 128, 0, 0) }
            assertFalse(resource.hasImageDecodeError)
            SvgaAudioSession(context, resource).use {
                it.prepare()
                assertTrue(directory.listFiles().orEmpty().filter { it.name.startsWith("svga-audio-") }
                    .any { session -> session.listFiles().orEmpty().any { audio -> audio.length() > 0 } })
            }
        } finally { bitmap.recycle() }
    }

    @Test fun viewUsesWarmExpandedDirectoryThroughInjectedLoader() {
        val request = request()
        runBlocking { engine.acquire(request.copy(width = 64, height = 64)) }
        val ready = CountDownLatch(1); val error = AtomicReference<Throwable?>()
        withTestActivity { activity ->
            lateinit var view: SVGAImageView
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                view = SVGAImageView(activity)
                activity.setContentView(view)
                view.loadSvga(request) {
                    loader = this@SvgaZipCacheTest.loader; autoPlay = false
                    onReady = { ready.countDown() }
                    onError = { error.set(it); ready.countDown() }
                }
            }
            try {
                assertTrue(ready.await(20, TimeUnit.SECONDS)); assertNull(error.get())
                assertEquals(1L, engine.zipExtractCount.get()); assertTrue(engine.zipCacheHits.get() >= 1)
            } finally { instrumentation.runOnMainSync { view.clearSvga() } }
        }
    }

    @Test fun nativeComposeUsesWarmExpandedDirectoryThroughInjectedLoader() {
        val request = request()
        runBlocking { engine.acquire(request.copy(width = 64, height = 64)) }
        val state = SvgaState()
        val ready = CountDownLatch(1); val error = AtomicReference<Throwable?>()
        withTestActivity { activity ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.setContent {
                    SvgaView(request, Modifier.size(128.dp), state, loader = loader, autoPlay = false,
                        onReady = { ready.countDown() }, onError = { error.set(it); ready.countDown() })
                }
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS)); assertNull(error.get())
            assertNull(state.error); assertEquals(SvgaLoadState.READY, state.loadState)
            assertEquals(1L, engine.zipExtractCount.get()); assertTrue(engine.zipCacheHits.get() >= 1)
        }
    }
}
