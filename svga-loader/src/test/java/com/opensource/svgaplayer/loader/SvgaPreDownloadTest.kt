package com.opensource.svgaplayer.loader

import android.content.ContextWrapper
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SvgaPreDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = temporary.root
        }
    }
    private lateinit var engine: SvgaEngine
    private lateinit var server: MockWebServer
    private val data by lazy {
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec"))
                zip.write("""{"movie":{"viewBox":{"width":100,"height":100},"fps":20,"frames":2},"images":{},"sprites":[]}""".toByteArray())
                zip.closeEntry()
                // A realistic transfer length without depending on app assets or bitmap decoders.
                zip.putNextEntry(ZipEntry("padding.bin"))
                zip.write(Random(0).nextBytes(256 * 1024)); zip.closeEntry()
            }
        }.toByteArray()
    }
    private fun request(path: String = "/gift.svga") = SvgaRequest(server.url(path).toString())
    private fun response(cache: String = "max-age=3600") = MockResponse()
        .setHeader("Cache-Control", cache).setBody(Buffer().write(data))
    private fun entries() = File(context.cacheDir, "svga-engine/sources").listFiles().orEmpty()
        .filter { !it.name.endsWith(".tmp") }
    private fun metadata() = JSONObject(File(entries().single(), "metadata").readText())

    @Before fun setUp() { server = MockWebServer().apply { start() }; engine = SvgaEngine(context) }
    @After fun tearDown() { engine.close(); server.shutdown() }

    @Test fun rawDownloadPersistsAcrossEngineRecreationWithoutPreparingResources() = runBlocking {
        val request = request()
        val progress = mutableListOf<SvgaDownloadProgress>()
        server.enqueue(response())
        engine.preDownload(request) { progress.add(it) }
        assertEquals(0, engine.decodeCount.get()); assertFalse(metadata().getBoolean("validated"))
        assertArrayEquals(data, File(entries().single(), "body").readBytes())
        assertTrue(progress.last().completed); assertEquals(data.size.toLong(), progress.last().bytesRead)
        engine.close(); engine = SvgaEngine(context)
        assertEquals(2, engine.acquire(request.copy(cacheOnly = true)).frames)
        assertEquals(1, engine.decodeCount.get()); assertEquals(1, server.requestCount)
        assertTrue(metadata().getBoolean("validated"))
    }

    @Test fun parsingAfterDownloadHonorsDiskAndMemoryPolicies() = runBlocking {
        for (policy in listOf(SvgaCachePolicy.ALL, SvgaCachePolicy.DISK)) {
            val request = request("/$policy.svga").copy(cachePolicy = policy)
            server.enqueue(response())
            val before = engine.decodeCount.get()
            engine.preDownload(request, parseAfterDownload = true)
            assertEquals(before + 1, engine.decodeCount.get())
            assertTrue(engine.acquire(request.copy(cacheOnly = true)).frames > 0)
            assertEquals(before + if (policy == SvgaCachePolicy.ALL) 1 else 2, engine.decodeCount.get())
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun rawDownloadFillsDiskEvenWhenResourceWasPreviouslyCachedOnlyInMemory() = runBlocking {
        val request = request()
        server.enqueue(response()); engine.acquire(request.copy(cachePolicy = SvgaCachePolicy.MEMORY))
        assertTrue(entries().isEmpty())
        server.enqueue(response()); engine.preDownload(request)
        assertEquals(2, server.requestCount); assertEquals(1, engine.decodeCount.get())
        assertFalse(metadata().getBoolean("validated"))
    }

    @Test fun rawAndParsedPreDownloadsAndNormalLoadShareOneTransferAndDecode() = runBlocking {
        server.enqueue(response().setBodyDelay(300, TimeUnit.MILLISECONDS))
        val request = request()
        val raw = async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request) }
        val parsed = async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request, true) }
        val normal = async(start = CoroutineStart.UNDISPATCHED) { engine.acquire(request) }
        withTimeout(10_000) { raw.await(); parsed.await(); assertEquals(2, normal.await().frames) }
        assertEquals(1, server.requestCount); assertEquals(1, engine.decodeCount.get())
        assertTrue(metadata().getBoolean("validated"))
    }

    @Test fun cancellingEitherSubscriberDoesNotCancelTheOther() = runBlocking {
        for (cancelPreDownload in listOf(true, false)) {
            server.enqueue(response().setBodyDelay(300, TimeUnit.MILLISECONDS))
            val request = request("/cancel-$cancelPreDownload.svga")
            val started = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                if (cancelPreDownload) engine.preDownload(request) { started.complete(Unit) }
                else engine.acquire(request) { started.complete(Unit) }
            }
            withTimeout(5_000) { started.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                if (cancelPreDownload) engine.acquire(request) else engine.preDownload(request)
            }
            first.cancelAndJoin(); withTimeout(10_000) { second.await() }
        }
        assertEquals(2, server.requestCount); assertEquals(1, engine.decodeCount.get())
    }

    @Test fun duplicatesDoNotUseMorePreDownloadSlotsAndNormalLoadsCanUseRemainingSlots() = runBlocking {
        engine.close(); engine = SvgaEngine(context, downloadConcurrency = 3, preDownloadConcurrency = 2)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(10, TimeUnit.SECONDS)); return response()
            }
        }
        val firstRequest = request("/first.svga")
        val jobs = mutableListOf<Deferred<Unit>>()
        try {
            repeat(4) { jobs += async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(firstRequest) } }
            jobs += async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request("/second.svga")) }
            jobs += async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request("/queued.svga")) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            val normal = async(start = CoroutineStart.UNDISPATCHED) { engine.acquire(request("/normal.svga")) }
            assertEquals("/normal.svga", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            release.countDown()
            withTimeout(10_000) { jobs.awaitAll(); normal.await() }
            assertEquals(4, server.requestCount)
        } finally { release.countDown(); jobs.forEach { it.cancel() } }
    }

    @Test fun totalDownloadLimitAlsoAppliesToPreDownloads() = runBlocking {
        engine.close(); engine = SvgaEngine(context, downloadConcurrency = 1, preDownloadConcurrency = 3)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(10, TimeUnit.SECONDS)); return response()
            }
        }
        val jobs = (0..2).map { async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request("/$it.svga")) } }
        try {
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            release.countDown(); withTimeout(10_000) { jobs.awaitAll() }
            assertEquals(3, server.requestCount)
        } finally { release.countDown(); jobs.forEach { it.cancel() } }
    }

    @Test fun noStoreAndDisabledDiskWritesDoNotCreateReusableFiles() = runBlocking {
        val noStore = request("/no-store.svga")
        server.enqueue(response("no-store")); engine.preDownload(noStore)
        assertTrue(entries().isEmpty())
        assertTrue(runCatching { engine.acquire(noStore.copy(cacheOnly = true)) }.isFailure)
        val noDisk = request("/no-disk.svga").copy(diskWrite = false)
        server.enqueue(response()); engine.preDownload(noDisk)
        assertTrue(entries().isEmpty()); assertEquals(0, engine.decodeCount.get())
    }

    @Test fun failedParsingRemovesAnUnvalidatedBodyAndAllowsNetworkRetry() = runBlocking {
        val request = request()
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
        engine.preDownload(request)
        assertEquals(0, engine.decodeCount.get()); assertEquals(1, entries().size)
        assertTrue(runCatching { engine.acquire(request) }.isFailure)
        assertTrue(entries().isEmpty())
        server.enqueue(response()); assertEquals(2, engine.acquire(request).frames)
        assertEquals(2, server.requestCount); assertTrue(metadata().getBoolean("validated"))
    }

    @Test fun lateRawPublicationDoesNotRestoreABodyRejectedByNormalLoading() = runBlocking {
        val request = request()
        val completed = CompletableDeferred<Unit>(); val allowRawPublication = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
        val raw = async(start = CoroutineStart.UNDISPATCHED) {
            engine.preDownload(request) { if (it.completed) { completed.complete(Unit); allowRawPublication.await() } }
        }
        try {
            withTimeout(5_000) { completed.await() }
            assertTrue(runCatching { withTimeout(5_000) { engine.acquire(request) } }.isFailure)
            allowRawPublication.complete(Unit); withTimeout(5_000) { raw.await() }
            assertEquals(1, server.requestCount); assertTrue(entries().isEmpty())
        } finally { allowRawPublication.complete(Unit); raw.cancelAndJoin() }
    }

    @Test fun lateRawDownloadWithDifferentDiskPolicyDoesNotRestoreRejectedContent() = runBlocking {
        val request = request()
        val completed = CompletableDeferred<Unit>(); val allowRawPublication = CompletableDeferred<Unit>()
        repeat(2) { server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken")) }
        val raw = async(start = CoroutineStart.UNDISPATCHED) {
            engine.preDownload(request.copy(diskRead = false)) {
                if (it.completed) { completed.complete(Unit); allowRawPublication.await() }
            }
        }
        try {
            withTimeout(5_000) { completed.await() }
            assertTrue(runCatching { withTimeout(5_000) { engine.acquire(request) } }.isFailure)
            allowRawPublication.complete(Unit); withTimeout(5_000) { raw.await() }
            assertEquals(2, server.requestCount); assertTrue(entries().isEmpty())

            // A later transfer can retry the same body once the overlapping tasks have ended.
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
            engine.preDownload(request)
            assertEquals(1, entries().size)
            assertTrue(runCatching { engine.acquire(request) }.isFailure)
            assertTrue(entries().isEmpty())
            server.enqueue(response()); assertEquals(2, engine.acquire(request).frames)
            assertEquals(4, server.requestCount); assertTrue(metadata().getBoolean("validated"))
        } finally { allowRawPublication.complete(Unit); raw.cancelAndJoin() }
    }

    @Test fun rawTransferStillDownloadingWhenAnotherPolicyRejectsDoesNotPublish() = runBlocking {
        val request = request()
        val completed = CompletableDeferred<Unit>(); val allowParsing = CompletableDeferred<Unit>()
        val allowRawResponse = CountDownLatch(1)
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
        val normal = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.acquire(request) {
                if (it.completed) { completed.complete(Unit); allowParsing.await() }
            } }
        }
        var raw: Deferred<Unit>? = null
        try {
            withTimeout(5_000) { completed.await() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(allowRawResponse.await(10, TimeUnit.SECONDS))
                    return MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken")
                }
            }
            val download = async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request.copy(diskRead = false)) }
            raw = download
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            allowParsing.complete(Unit); assertTrue(withTimeout(5_000) { normal.await() }.isFailure)
            allowRawResponse.countDown(); withTimeout(5_000) { download.await() }
            assertEquals(2, server.requestCount); assertTrue(entries().isEmpty())
        } finally {
            allowParsing.complete(Unit); allowRawResponse.countDown()
            normal.cancelAndJoin(); raw?.cancelAndJoin()
        }
    }

    @Test fun rejectingOneBodyDoesNotBlockDifferentContentFromSameSource() = runBlocking {
        val request = request()
        val completed = CompletableDeferred<Unit>(); val allowRawPublication = CompletableDeferred<Unit>()
        server.enqueue(response())
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
        val raw = async(start = CoroutineStart.UNDISPATCHED) {
            engine.preDownload(request.copy(diskRead = false)) {
                if (it.completed) { completed.complete(Unit); allowRawPublication.await() }
            }
        }
        try {
            withTimeout(5_000) { completed.await() }
            assertTrue(runCatching { withTimeout(5_000) { engine.acquire(request) } }.isFailure)
            allowRawPublication.complete(Unit); withTimeout(5_000) { raw.await() }
            assertArrayEquals(data, File(entries().single(), "body").readBytes())
            assertEquals(2, engine.acquire(request.copy(cacheOnly = true)).frames)
            assertEquals(2, server.requestCount); assertTrue(metadata().getBoolean("validated"))
        } finally { allowRawPublication.complete(Unit); raw.cancelAndJoin() }
    }

    @Test fun cancelledPreDownloadResumesInNormalLoading() = runBlocking {
        val request = request()
        server.enqueue(response().setHeader("ETag", "\"v1\"").throttleBody(4096, 20, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val raw = launch { engine.preDownload(request) { if (it.bytesRead > 0) started.complete(Unit) } }
        withTimeout(5_000) { started.await() }; raw.cancelAndJoin()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                assertTrue(offset in 1 until data.size); assertEquals("\"v1\"", request.getHeader("If-Range"))
                return response().setResponseCode(206).setHeader("ETag", "\"v1\"")
                    .setHeader("Content-Range", "bytes $offset-${data.size - 1}/${data.size}")
                    .setBody(Buffer().write(data, offset, data.size - offset))
            }
        }
        assertEquals(2, withTimeout(10_000) { engine.acquire(request) }.frames)
        assertEquals(2, server.requestCount); assertTrue(metadata().getBoolean("validated"))
    }

    @Test fun differentHeadersAreNotMerged() = runBlocking {
        server.enqueue(response().setBodyDelay(200, TimeUnit.MILLISECONDS)); server.enqueue(response())
        val request = request()
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request.copy(headers = mapOf("Authorization" to "a"))) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.preDownload(request.copy(headers = mapOf("Authorization" to "b"))) }
        withTimeout(10_000) { first.await(); second.await() }
        assertEquals(2, server.requestCount); assertEquals(2, entries().size)
    }

    @Test fun staleRawBodyCanBeConditionallyValidatedWithoutParsing() = runBlocking {
        val request = request()
        server.enqueue(response("max-age=0").setHeader("ETag", "\"v1\""))
        engine.preDownload(request)
        server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600"))
        engine.preDownload(request)
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("\"v1\"", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
        assertEquals(0, engine.decodeCount.get()); assertFalse(metadata().getBoolean("validated"))
        assertArrayEquals(data, File(entries().single(), "body").readBytes())
        assertEquals(2, engine.acquire(request).frames); assertEquals(2, server.requestCount)
    }

    @Test fun lateRawDownloadWithDifferentDiskPolicyDoesNotDowngradeValidatedContent() = runBlocking {
        val request = request()
        val completed = CompletableDeferred<Unit>(); val allowRawPublication = CompletableDeferred<Unit>()
        server.enqueue(response()); server.enqueue(response())
        val raw = async(start = CoroutineStart.UNDISPATCHED) {
            engine.preDownload(request.copy(diskRead = false)) {
                if (it.completed) { completed.complete(Unit); allowRawPublication.await() }
            }
        }
        try {
            withTimeout(5_000) { completed.await() }
            assertEquals(2, engine.acquire(request).frames); assertTrue(metadata().getBoolean("validated"))
            allowRawPublication.complete(Unit); withTimeout(5_000) { raw.await() }
            assertTrue(metadata().getBoolean("validated"))
        } finally { allowRawPublication.complete(Unit); raw.cancelAndJoin() }
    }

    @Test fun parsingPreDownloadDoesNotPublishInvalidContent() = runBlocking {
        val request = request()
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("broken"))
        assertTrue(runCatching { engine.preDownload(request, parseAfterDownload = true) }.isFailure)
        assertTrue(entries().isEmpty())
        server.enqueue(response()); engine.preDownload(request, parseAfterDownload = true)
        assertTrue(metadata().getBoolean("validated")); assertEquals(2, server.requestCount)
    }
}
