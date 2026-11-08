package com.example.ponycui_home.svgaplayer

import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SvgaWeakCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val request get() = SvgaRequest(SvgaSource.Asset("rose_2.0.0.svga"), 128, 128)

    @Test fun zeroLruStillReusesLiveResourceAndClearRemovesBothIndexes() = runBlocking {
        val engine = SvgaEngine(context, memoryBytes = 0)
        try {
            val first = engine.acquire(request)
            assertSame(first, engine.acquire(request))
            assertEquals(1, engine.decodeCount.get()); assertEquals(1, engine.weakMemoryHits.get())
            engine.clearMemory()
            assertNotSame(first, engine.acquire(request))
            assertEquals(2, engine.decodeCount.get())
        } finally { engine.close() }
    }

    @Test fun requestCanEnableEachLayerSeparatelyOrDisableBoth() = runBlocking {
        val engine = SvgaEngine(context)
        try {
            val weakOnly = request.copy(memoryCache = false, weakMemoryCache = true)
            val weak = engine.acquire(weakOnly)
            assertSame(weak, engine.acquire(weakOnly))
            val strongOnly = request.copy(memoryCache = true, weakMemoryCache = false)
            val strong = engine.acquire(strongOnly)
            assertNotSame(weak, strong); assertSame(strong, engine.acquire(strongOnly))
            val neither = request.copy(memoryCache = false, weakMemoryCache = false)
            val uncached = engine.acquire(neither)
            assertNotSame(uncached, engine.acquire(neither))
            // Opting out does not delete resources retained by other requests.
            assertSame(weak, engine.acquire(weakOnly)); assertSame(strong, engine.acquire(strongOnly))
        } finally { engine.close() }
    }

    @Test fun engineSwitchAndDecodeIdentityAreRespected() = runBlocking {
        val disabled = SvgaEngine(context, memoryBytes = 0, weakMemoryCacheEnabled = false)
        try {
            val first = disabled.acquire(request)
            assertNotSame(first, disabled.acquire(request)); assertEquals(0, disabled.weakMemoryHits.get())
        } finally { disabled.close() }
        val engine = SvgaEngine(context, memoryBytes = 0)
        try {
            val first = engine.acquire(request)
            assertNotSame(first, engine.acquire(request.copy(width = 256)))
            assertNotSame(first, engine.acquire(request.copy(namespace = "other")))
            assertSame(first, engine.acquire(request))
        } finally { engine.close() }
    }

    @Test fun weakCacheRevalidatesExpiryAndRefreshNoStoreInvalidatesLiveEntries() = runBlocking {
        val server = MockWebServer(); server.start()
        val engine = SvgaEngine(context, memoryBytes = 0)
        try {
            val data = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
            fun response(control: String) = MockResponse().setHeader("Cache-Control", control)
                .setHeader("ETag", "\"v1\"").setBody(Buffer().write(data))
            val remote = SvgaRequest(server.url("/weak.svga").toString()).copy(width = 128, height = 128)
            server.enqueue(response("max-age=0"))
            val first = engine.acquire(remote)
            server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600"))
            assertSame(first, engine.acquire(remote)); assertEquals(2, server.requestCount)
            assertSame(first, engine.acquire(remote)); assertEquals(2, server.requestCount)
            server.enqueue(response("no-store"))
            val refreshed = engine.acquire(remote.copy(refresh = true))
            assertNotSame(first, refreshed)
            assertTrue(runCatching { engine.acquire(remote.copy(cacheOnly = true)) }.isFailure)
            server.enqueue(response("no-store"))
            assertNotSame(refreshed, engine.acquire(remote))
            assertEquals(4, server.requestCount)
        } finally { engine.close(); server.shutdown() }
    }
}
