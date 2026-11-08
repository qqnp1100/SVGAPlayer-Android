package com.example.ponycui_home.svgaplayer

import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SvgaResumeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bytes get() = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
    private fun full(data: ByteArray, etag: String = "\"v1\"") = MockResponse()
        .setHeader("ETag", etag).setHeader("Cache-Control", "max-age=3600").setBody(Buffer().write(data))
    private fun range(data: ByteArray, offset: Long) = full(data.copyOfRange(offset.toInt(), data.size))
        .setResponseCode(206).setHeader("Content-Range", "bytes $offset-${data.size - 1}/${data.size}")
    private fun offset(request: RecordedRequest): Long = request.getHeader("Range")!!
        .removePrefix("bytes=").removeSuffix("-").toLong()
    private fun assertCached(data: ByteArray) {
        val bodies = File(context.cacheDir, "svga-engine/sources").listFiles().orEmpty().map { File(it, "body") }.filter { it.isFile }
        assertTrue("Completed disk cache must contain the exact downloaded bytes", bodies.any { it.readBytes().contentEquals(data) })
    }
    private suspend fun cancelAfterProgress(engine: SvgaEngine, request: SvgaRequest) = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val task = launch { engine.acquire(request) { if (it.bytesRead > 0 && !it.completed) started.complete(Unit) } }
        try { withTimeout(10_000) { started.await() } } finally { task.cancelAndJoin() }
    }

    @Test fun suppliedCdnSamplesResumeAfterCancellationOnDevice() = runBlocking {
        val responses = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Response>()
        val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            responses.add(response)
            val original = response.body!!
            val throttled = object : okio.ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    Thread.sleep(8) // Ensure a deterministic cancellation point on fast Wi-Fi.
                    return super.read(sink, minOf(byteCount, 8192))
                }
            }
            response.newBuilder().body(object : okhttp3.ResponseBody() {
                private val buffered = throttled.buffer()
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = buffered
            }).build()
        }.build()
        var engine = SvgaEngine(context, client = client)
        try {
            for (name in listOf("head_bg_vip10", "head_bg_vip9")) {
                engine.clearDisk(); engine.clearMemory(); responses.clear()
                val request = SvgaRequest("https://pic.vchat-onlie.com/$name.svga").copy(width = 256, height = 256)
                cancelAfterProgress(engine, request)
                engine.close(); engine = SvgaEngine(context, client = client)
                val events = mutableListOf<SvgaDownloadProgress>()
                assertTrue(engine.acquire(request) { events.add(it) }.frames > 0)
                assertEquals(2, responses.size)
                assertEquals(206, responses.last().code)
                assertNotNull(responses.last().request.header("Range"))
                assertEquals(responses.first().header("ETag"), responses.last().request.header("If-Range"))
                assertTrue(events.first().bytesRead > 0); assertTrue(events.last().completed)
                assertEquals(events.last().totalBytes, events.last().bytesRead)
                android.util.Log.i("SvgaResumeTest", "$name resumed ${responses.last().request.header("Range")} -> ${responses.last().header("Content-Range")}")
            }
        } finally { engine.close() }
    }

    @Test fun cancellationResumesAcrossEngineRecreationAndReportsAbsoluteProgress() = runBlocking {
        val server = MockWebServer(); server.start()
        var engine = SvgaEngine(context)
        try {
            engine.clearDisk()
            val data = bytes
            val request = SvgaRequest(server.url("/cancel.svga").toString())
            server.enqueue(full(data).throttleBody(4096, 30, TimeUnit.MILLISECONDS))
            cancelAfterProgress(engine, request)
            server.takeRequest(); engine.close(); engine = SvgaEngine(context)
            var resumedAt = 0L
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    resumedAt = offset(request)
                    assertEquals("\"v1\"", request.getHeader("If-Range"))
                    assertEquals("identity", request.getHeader("Accept-Encoding"))
                    assertNull(request.getHeader("If-None-Match"))
                    return range(data, resumedAt).throttleBody(16384, 10, TimeUnit.MILLISECONDS)
                }
            }
            val progress = mutableListOf<SvgaDownloadProgress>()
            assertTrue(engine.acquire(request) { progress.add(it) }.frames > 0)
            assertTrue(resumedAt in 1 until data.size.toLong())
            assertTrue(progress.first().bytesRead >= resumedAt)
            assertTrue(progress.zipWithNext().all { (a, b) -> a.bytesRead <= b.bytesRead })
            assertEquals(data.size.toLong(), progress.last().bytesRead); assertTrue(progress.last().completed)
            assertCached(data); assertEquals(2, server.requestCount)
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun disconnectedBodyResumesAndAnotherDisconnectAdvancesCheckpoint() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            engine.clearDisk()
            val data = bytes; val request = SvgaRequest(server.url("/disconnect.svga").toString())
            server.enqueue(full(data).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertTrue(runCatching { engine.acquire(request) }.isFailure)
            server.takeRequest()
            val offsets = mutableListOf<Long>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val start = offset(request); offsets.add(start)
                    return range(data, start).apply {
                        if (offsets.size == 1) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                }
            }
            assertTrue(runCatching { engine.acquire(request) }.isFailure)
            assertTrue(engine.acquire(request).frames > 0)
            assertEquals(2, offsets.size); assertTrue(offsets[1] > offsets[0]); assertCached(data)
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun serverIgnoringRangeReplacesOldPrefixWithNewRepresentation() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            engine.clearDisk()
            val data = bytes; val request = SvgaRequest(server.url("/changed.svga").toString())
            server.enqueue(full(data).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertTrue(runCatching { engine.acquire(request) }.isFailure); server.takeRequest()
            val replacement = context.assets.open("heartbeat.svga").use { it.readBytes() }
            server.enqueue(full(replacement, "\"v2\""))
            assertTrue(engine.acquire(request).frames > 0)
            assertNotNull(server.takeRequest().getHeader("Range")); assertCached(replacement)
            assertEquals(2, server.requestCount)
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun invalidRangesAnd416RetryOnceWithoutRangeOrOldPrefix() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            val data = bytes
            for (kind in 0..3) {
                engine.clearDisk(); engine.clearMemory()
                val request = SvgaRequest(server.url("/invalid-$kind.svga").toString())
                server.enqueue(full(data).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                assertTrue(runCatching { engine.acquire(request) }.isFailure); server.takeRequest()
                val bad = when (kind) {
                    0 -> MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${data.size}")
                    1 -> range(data, 0) // Wrong offset, even though the body is a valid SVGA.
                    2 -> full(data).setResponseCode(206).setHeader("Content-Range", "bytes invalid")
                    else -> range(data, data.size / 2L).setHeader("ETag", "\"changed\"")
                }
                server.enqueue(bad); server.enqueue(full(data))
                assertTrue(engine.acquire(request).frames > 0)
                assertNotNull(server.takeRequest().getHeader("Range"))
                val retry = server.takeRequest()
                assertNull(retry.getHeader("Range")); assertNull(retry.getHeader("If-Range")); assertCached(data)
            }
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun nonCacheableOrUnvalidatedDownloadsNeverRetainAPrefix() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            val data = bytes
            for (kind in 0..5) {
                engine.clearDisk(); engine.clearMemory()
                val base = SvgaRequest(server.url("/disabled-$kind.svga").toString())
                val request = when (kind) {
                    0 -> base.copy(cachePolicy = SvgaCachePolicy.NONE)
                    1 -> base.copy(resumeDownloads = false)
                    2 -> base.copy(diskWrite = false)
                    else -> base
                }
                val first = full(data).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                when (kind) {
                    3 -> first.setHeader("Cache-Control", "no-store")
                    4 -> first.setHeader("ETag", "W/\"weak\"")
                    5 -> first.removeHeader("ETag")
                }
                server.enqueue(first)
                assertTrue(runCatching { engine.acquire(request) }.isFailure); server.takeRequest()
                server.enqueue(full(data)); assertTrue(engine.acquire(request).frames > 0)
                assertNull(server.takeRequest().getHeader("Range"))
            }
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun clearDiskAndRefreshDiscardCheckpointsAndHeadersIsolateThem() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            val data = bytes
            for (kind in 0..3) {
                engine.clearDisk(); engine.clearMemory()
                val request = SvgaRequest(server.url("/bypass-$kind.svga").toString())
                server.enqueue(full(data).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                assertTrue(runCatching { engine.acquire(request) }.isFailure); server.takeRequest()
                if (kind == 0) engine.clearDisk()
                val next = when (kind) {
                    1 -> request.copy(refresh = true)
                    2 -> request.copy(headers = mapOf("Authorization" to "Bearer different-user"))
                    3 -> request.copy(diskRead = false)
                    else -> request
                }
                server.enqueue(full(data)); assertTrue(engine.acquire(next).frames > 0)
                assertNull(server.takeRequest().getHeader("Range"))
            }
        } finally { engine.close(); server.shutdown() }
    }

    @Test fun chunkedPrefixResumesAndSharedWaiterCancellationDoesNotRestartDownload() = runBlocking {
        val server = MockWebServer(); server.start(); val engine = SvgaEngine(context)
        try {
            engine.clearDisk()
            val data = bytes; val request = SvgaRequest(server.url("/chunked.svga").toString())
            server.enqueue(full(data).setChunkedBody(Buffer().write(data), 4096).throttleBody(4096, 30, TimeUnit.MILLISECONDS))
            cancelAfterProgress(engine, request); server.takeRequest()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = range(data, offset(request)).throttleBody(4096, 5, TimeUnit.MILLISECONDS)
            }
            val started = CompletableDeferred<Unit>()
            val first = async { engine.acquire(request) { if (it.bytesRead > 0) started.complete(Unit) } }
            withTimeout(10_000) { started.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.acquire(request.copy(width = 128, height = 128)) }
            first.cancelAndJoin()
            assertTrue(second.await().frames > 0); assertEquals(2, server.requestCount); assertCached(data)
        } finally { engine.close(); server.shutdown() }
    }
}
