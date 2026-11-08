package com.opensource.svgaplayer.loader

import android.content.ContextWrapper
import android.content.res.AssetManager
import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowArscAssetManager9
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Counts actual stream consumption without timing-dependent performance assertions. */
@Implements(value = AssetManager::class, minSdk = 28, maxSdk = 28)
class ReadTrackingAssetManager : ShadowArscAssetManager9() {
    @RealObject private lateinit var manager: AssetManager
    var input: ((String) -> InputStream)? = null
    val opens = AtomicInteger()
    val bytesRead = AtomicLong()
    val closes = AtomicInteger()

    @Implementation protected fun open(name: String): InputStream {
        val factory = input ?: return Shadow.directlyOn(manager, AssetManager::class.java, "open",
            ClassParameter.from(String::class.java, name))
        val stream = factory(name)
        opens.incrementAndGet()
        return object : FilterInputStream(stream) {
            private val closed = AtomicBoolean()
            override fun read(): Int = `in`.read().also { if (it >= 0) bytesRead.incrementAndGet() }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                `in`.read(b, off, len).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
            override fun close() {
                if (closed.compareAndSet(false, true)) { closes.incrementAndGet(); super.close() }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ReadTrackingAssetManager::class])
class SvgaAssetReadTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var engine: SvgaEngine
    private lateinit var assets: AssetManager
    private lateinit var tracker: ReadTrackingAssetManager
    private lateinit var data: ByteArray
    private val request get() = SvgaRequest(SvgaSource.Asset("gift.svga"))

    @Before fun setUp() {
        data = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec"))
                zip.write("""{"movie":{"viewBox":{"width":100,"height":80},"fps":20,"frames":2},"images":{},"sprites":[]}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assets = ReflectionHelpers.newInstance(AssetManager::class.java)
        tracker = Shadow.extract(assets)
        tracker.input = { path ->
            if (path != "gift.svga") throw FileNotFoundException(path)
            data.inputStream()
        }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = temporary.root
            override fun getAssets() = this@SvgaAssetReadTest.assets
        }
        engine = SvgaEngine(context, decodeConcurrency = 1)
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.close()
        if (::assets.isInitialized) assets.close()
    }

    private suspend fun awaitSubscribers(count: Int) {
        val sources = ReflectionHelpers.getField<Any>(engine, "sources")
        val flights = ReflectionHelpers.getField<Map<*, *>>(sources, "flights")
        withTimeout(10_000) {
            while (!synchronized(flights) {
                flights.values.any { ReflectionHelpers.getField<Any?>(it, "result") != null &&
                    ReflectionHelpers.getField<Int>(it, "users") == count }
            }) delay(5)
        }
    }

    @Test fun coldLoadOpensAndConsumesTheAssetOnlyOnce() = runBlocking {
        assertEquals(2, engine.acquire(request).frames)
        assertEquals(1, tracker.opens.get())
        assertEquals(data.size.toLong(), tracker.bytesRead.get())
        assertEquals(1, tracker.closes.get())
        assertEquals(1L, engine.decodeCount.get())
    }

    @Test fun strongAndWeakMemoryHitsDoNotReopenTheAsset() = runBlocking {
        val first = engine.acquire(request)
        assertSame(first, engine.acquire(request))
        assertSame(first, engine.acquire(request.copy(memoryCache = false, weakMemoryCache = true)))
        assertEquals(1, tracker.opens.get())
        assertEquals(data.size.toLong(), tracker.bytesRead.get())
        assertEquals(1L, engine.decodeCount.get())
        assertEquals(1L, engine.memoryHits.get())
        assertEquals(1L, engine.weakMemoryHits.get())
    }

    @Test fun concurrentIdenticalRequestsShareOneAssetReadAndDecode() = runBlocking {
        val decodes = ReflectionHelpers.getField<Semaphore>(engine, "decodes")
        decodes.acquire()
        val tasks = (0 until 8).map { async(Dispatchers.Default) { engine.acquire(request) } }
        try { awaitSubscribers(8) } finally { decodes.release() }
        val results = withTimeout(10_000) { tasks.awaitAll() }
        assertTrue(results.all { it === results.first() })
        assertEquals(1, tracker.opens.get())
        assertEquals(data.size.toLong(), tracker.bytesRead.get())
        assertEquals(1L, engine.decodeCount.get())
    }

    @Test fun refreshAndMemoryClearEachReadTheAssetOnce() = runBlocking {
        val first = engine.acquire(request)
        val refreshed = engine.acquire(request.copy(refresh = true))
        assertNotSame(first, refreshed)
        engine.clearMemory()
        assertNotSame(refreshed, engine.acquire(request))
        assertEquals(3, tracker.opens.get())
        assertEquals(3L * data.size, tracker.bytesRead.get())
        assertEquals(3L, engine.decodeCount.get())
    }

    @Test fun distinctSizesAndUncachedLoadsEachReadOncePerDecode() = runBlocking {
        val first = engine.acquire(request.copy(width = 64, height = 64))
        val second = engine.acquire(request.copy(width = 128, height = 128))
        assertNotSame(first, second)
        repeat(2) { engine.acquire(request.copy(cachePolicy = SvgaCachePolicy.NONE)) }
        assertEquals(4, tracker.opens.get())
        assertEquals(4L * data.size, tracker.bytesRead.get())
        assertEquals(4L, engine.decodeCount.get())
    }

    @Test fun parsingPreDownloadReadsOnceAndPopulatesMemoryCache() = runBlocking {
        engine.preDownload(request, parseAfterDownload = true)
        assertEquals(2, engine.acquire(request).frames)
        assertEquals(1, tracker.opens.get())
        assertEquals(data.size.toLong(), tracker.bytesRead.get())
        assertEquals(1L, engine.decodeCount.get())
    }

    @Test fun downloadOnlyStillChecksTheAssetWithoutDecoding() = runBlocking {
        engine.preDownload(request)
        assertEquals(1, tracker.opens.get())
        assertEquals(data.size.toLong(), tracker.bytesRead.get())
        assertEquals(1, tracker.closes.get())
        assertEquals(0L, engine.decodeCount.get())
        val error = runCatching {
            engine.preDownload(request.copy(source = SvgaSource.Asset("missing.svga")))
        }.exceptionOrNull()
        assertTrue(error is FileNotFoundException)
    }

    @Test fun downloadOnlyRetainsTheInputLimitAndClosesOversizedStreams() = runBlocking {
        tracker.input = {
            object : InputStream() {
                var remaining = SvgaResource.MAX_INPUT_BYTES + 1
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val n = minOf(remaining, len.toLong()).toInt()
                    remaining -= n
                    return n
                }
            }
        }
        val error = runCatching { engine.preDownload(request) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Input too large", error?.message)
        assertEquals(1, tracker.closes.get())
        assertEquals(0L, engine.decodeCount.get())
    }

    @Test fun oversizedPackagedAssetIsRejectedWithoutReadingItsBody() = runBlocking {
        tracker.input = {
            object : InputStream() {
                override fun available() = (SvgaResource.MAX_INPUT_BYTES + 1).toInt()
                override fun read(): Int = error("Oversized asset must be rejected before reading")
            }
        }
        val error = runCatching { engine.acquire(request) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Input too large", error?.message)
        assertEquals(0L, tracker.bytesRead.get())
        assertEquals(1, tracker.closes.get())
    }

    @Test fun concurrentDownloadOnlyRequestsValidateTheAssetOnce() = runBlocking {
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        tracker.input = {
            object : FilterInputStream(data.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reading.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                    return `in`.read(b, off, len)
                }
            }
        }
        val tasks = (0 until 8).map { async(Dispatchers.Default) { engine.preDownload(request) } }
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            awaitSubscribers(8)
        } finally { resume.countDown() }
        withTimeout(10_000) { tasks.awaitAll() }
        assertEquals(1, tracker.opens.get())
        assertEquals(data.size.toLong(), tracker.bytesRead.get())
        assertEquals(1, tracker.closes.get())
        assertEquals(0L, engine.decodeCount.get())
    }

    @Test fun cancelledDownloadOnlyClosesTheStreamAndAllowsRetry() = runBlocking {
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        tracker.input = {
            object : InputStream() {
                override fun read(): Int { reading.countDown(); check(resume.await(10, TimeUnit.SECONDS)); return 0 }
                override fun read(b: ByteArray, off: Int, len: Int): Int { read(); return 1 }
            }
        }
        val task = async(Dispatchers.Default) { engine.preDownload(request) }
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            task.cancel()
        } finally { resume.countDown() }
        withTimeout(10_000) { task.join() }
        assertTrue(task.isCancelled)
        assertEquals(1, tracker.closes.get())
        tracker.input = { data.inputStream() }
        assertEquals(2, engine.acquire(request).frames)
    }
}
