package com.example.ponycui_home.svgaplayer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.opensource.svgaplayer.compose.*
import com.opensource.svgaplayer.loader.*
import com.opensource.svgaplayer.coil3.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SvgaComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun resizingKeepsTheCurrentFrameUntilNewBindingsAreReady() {
        val context = compose.activity
        val directory = java.io.File(context.cacheDir, "compose-resize-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        val engine = SvgaEngine(context)
        val loader = SvgaImageLoader(context, engine)
        val state = SvgaState()
        var viewSize by mutableIntStateOf(120)
        var show by mutableStateOf(true)
        var readyCount = 0
        fun png(color: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                return java.io.ByteArrayOutputStream().also {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }.toByteArray()
            } finally { bitmap.recycle() }
        }
        val frame = org.json.JSONObject().put("alpha", 1.0)
            .put("layout", org.json.JSONObject().put("width", 100).put("height", 100))
            .put("transform", org.json.JSONObject().put("a", 1.0).put("d", 1.0))
        val spec = org.json.JSONObject().put("movie", org.json.JSONObject().put("viewBox",
            org.json.JSONObject().put("width", 100).put("height", 100)).put("fps", 20).put("frames", 200))
            .put("images", org.json.JSONObject()).put("sprites", org.json.JSONArray().put(
                org.json.JSONObject().put("imageKey", "avatar").put("frames",
                    org.json.JSONArray().apply { repeat(200) { put(frame) } })))
        val file = java.io.File(directory, "resize.svga")
        java.util.zip.ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("movie.spec")); it.write(spec.toString().toByteArray()); it.closeEntry()
        }
        val bindings = svgaBindings { image("avatar", server.url("/avatar.png").toString()) }
        fun centerPixel(): Color {
            val image = compose.onNodeWithTag("resize-svga").captureToImage()
            return image.toPixelMap()[image.width / 2, image.height / 2]
        }
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(png(android.graphics.Color.RED))))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(png(android.graphics.Color.BLUE)))
                .setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS))
            compose.setContent {
                if (show) SvgaView(file, Modifier.size(viewSize.dp).background(Color.Black).testTag("resize-svga"),
                    state, bindings, cachePolicy = SvgaCachePolicy.NONE, loader = loader, onReady = { readyCount++ })
            }
            compose.waitUntil(20_000) { state.loadState == SvgaLoadState.READY }
            assertEquals(Color.Red, centerPixel())
            for (smaller in listOf(100, 90, 80)) {
                compose.runOnUiThread { viewSize = smaller }
                compose.waitForIdle()
            }
            Thread.sleep(250)
            assertEquals("Shrinking must not request new bindings", 1, server.requestCount)
            assertEquals(1, readyCount)
            val previousFrame = state.currentFrame
            compose.runOnUiThread { viewSize = 240 }
            compose.waitUntil(10_000) { server.requestCount >= 2 }
            assertEquals("Keep the old frame while the new image is loading", Color.Red, centerPixel())
            compose.waitUntil(10_000) { state.currentFrame > previousFrame }
            compose.waitUntil(10_000) { centerPixel() == Color.Blue }
            assertEquals("A size upgrade must not fire onReady again", 1, readyCount)
            assertNull(state.error)
        } finally {
            compose.runOnUiThread { show = false }; compose.waitForIdle()
            loader.close(); engine.close(); server.shutdown(); directory.deleteRecursively()
        }
    }

    @Test fun deferredRequestUsesAnEagerResourceInCompose() {
        val state = SvgaState()
        val engine = SvgaEngine(compose.activity)
        val loader = SvgaImageLoader(compose.activity, engine)
        val request = SvgaRequest(SvgaSource.Asset("rose_2.0.0.svga"), 128, 128)
        try {
            val eager = runBlocking { engine.acquire(request) }
            compose.setContent {
                SvgaView(request.copy(inBitmap = true), Modifier.size(128.dp), state,
                    loader = loader, autoPlay = false)
            }
            compose.waitUntil(20_000) { state.loadState == SvgaLoadState.READY }
            assertEquals("Compose must reuse the prepared eager resource", 1L, engine.decodeCount.get())
            assertTrue(engine.memoryHits.get() > 0)
            assertSame(eager, runBlocking { engine.acquire(request) })
            compose.runOnUiThread { state.seekToProgress(1f) }
            compose.waitForIdle()
            assertNull(state.error)
        } finally { loader.close(); engine.close() }
    }

    @Test fun networkProgressIsOnMainAndResetsForLocalSource() {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        val state = SvgaState()
        val bytes = compose.activity.assets.open("rose_2.0.0.svga").use { it.readBytes() }
        val events = mutableListOf<SvgaDownloadProgress>()
        var source by mutableStateOf<SvgaSource>(SvgaSource.Remote(server.url("/compose-progress.svga").toString()))
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)))
            compose.setContent {
                SvgaView(source, Modifier.size(160.dp), state, autoPlay = false,
                    onDownloadProgress = {
                        assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                        events.add(it)
                    })
            }
            compose.waitUntil(20_000) { state.loadState == SvgaLoadState.READY }
            compose.runOnUiThread {
                assertTrue(events.last().completed)
                assertEquals(bytes.size.toLong(), state.downloadProgress!!.bytesRead)
                source = SvgaSource.Asset("rose_2.0.0.svga")
            }
            compose.waitUntil(20_000) { state.downloadProgress == null && state.loadState == SvgaLoadState.READY }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun pauseResumeBindingsRecompositionAndDisposal() {
        val state = SvgaState()
        val context = compose.activity
        val engine = SvgaEngine(context)
        val loader = SvgaImageLoader(context, engine)
        var show by mutableStateOf(true)
        var binding by mutableStateOf(SvgaBindings.Empty)
        var recompose by mutableIntStateOf(0)
        var compositions = 0
        compose.setContent {
            recompose
            SideEffect { compositions++ }
            if (show) SvgaView(SvgaSource.Asset("rose_2.0.0.svga"), Modifier.size(180.dp), state,
                bindings = binding, loader = loader)
        }
        compose.waitUntil(20_000) { state.loadState == SvgaLoadState.READY }
        compose.waitUntil(5_000) { state.currentFrame > 0 }
        var frozen = 0
        compose.runOnUiThread { state.pause(); frozen = state.currentFrame }
        Thread.sleep(200)
        compose.runOnUiThread { assertEquals(frozen, state.currentFrame); state.seekToProgress(.5f); assertTrue(state.progress >= .49f) }
        val prepared = engine.decodeCount.get()
        val before = compositions
        compose.runOnUiThread { state.resume() }
        Thread.sleep(200)
        compose.runOnUiThread { assertEquals(before, compositions); recompose++
            binding = svgaBindings { hidden("nonexistent") }
        }
        compose.waitUntil(5_000) { state.loadState == SvgaLoadState.READY }
        compose.runOnUiThread { assertEquals(prepared, engine.decodeCount.get()); show = false }
        compose.waitForIdle()
        frozen = state.currentFrame
        Thread.sleep(200)
        compose.runOnUiThread { assertEquals(frozen, state.currentFrame) }
        loader.close(); engine.close()
    }

    @Test fun backgroundPausesAndForegroundResumes() {
        val state = SvgaState()
        compose.setContent { SvgaView(SvgaSource.Asset("rose_2.0.0.svga"), Modifier.size(160.dp), state, autoPlay = false) }
        compose.waitUntil(20_000) { state.loadState == SvgaLoadState.READY }
        compose.runOnUiThread { state.resume() }
        compose.waitUntil(5_000) { state.currentFrame > 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val paused = state.currentFrame
        Thread.sleep(200)
        assertEquals(paused, state.currentFrame)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { state.currentFrame != paused }
    }
}
