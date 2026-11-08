package com.opensource.svgaplayer.loader

import android.content.ContextWrapper
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SvgaCacheDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var cacheDirectory: File
    private lateinit var assetManager: AssetManager
    private lateinit var engine: SvgaEngine
    private lateinit var server: MockWebServer
    private val staging get() = File(cacheDirectory, "svga-engine/staging")
    private val engineDirectory get() = File(cacheDirectory, "svga-engine")
    private val assetRequest get() = SvgaRequest(SvgaSource.Asset("recovery.svga"), cachePolicy = SvgaCachePolicy.NONE)
    private val data by lazy {
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec"))
                zip.write("""{"movie":{"viewBox":{"width":100,"height":100},"fps":20,"frames":2},"images":{},"sprites":[]}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }

    @Before fun setUp() {
        val assetPackage = temporary.newFile("assets.apk")
        ZipOutputStream(assetPackage.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/recovery.svga"))
            zip.write(data)
            zip.closeEntry()
        }
        assetManager = ReflectionHelpers.newInstance(AssetManager::class.java)
        val cookie = ReflectionHelpers.callInstanceMethod<Int>(assetManager, "addAssetPath",
            ClassParameter.from(String::class.java, assetPackage.absolutePath))
        assertTrue("Test asset package must be installed", cookie != 0)
        assetManager.open("recovery.svga").use { assertArrayEquals(data, it.readBytes()) }
        cacheDirectory = temporary.newFolder("cache")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = cacheDirectory
            override fun getAssets() = assetManager
        }
        engine = SvgaEngine(context)
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.close()
        if (::server.isInitialized) server.shutdown()
        if (::assetManager.isInitialized) assetManager.close()
    }

    private fun removeCacheDirectory(directory: File) {
        val cacheRoot = cacheDirectory.canonicalFile.toPath()
        val target = directory.canonicalFile.toPath()
        assertTrue("Only this test's engine cache may be removed", target.startsWith(cacheRoot) && target != cacheRoot)
        assertTrue(directory.deleteRecursively())
    }

    private fun response() = MockResponse().setHeader("Cache-Control", "max-age=3600")
        .setBody(Buffer().write(data))

    @Test fun assetLoadingRecoversAfterStagingIsRemoved() = runBlocking {
        assertEquals(2, engine.acquire(assetRequest).frames)
        removeCacheDirectory(staging)
        assertEquals(2, engine.acquire(assetRequest).frames)
        assertTrue(staging.isDirectory)
        assertEquals(2L, engine.decodeCount.get())
    }

    @Test fun assetLoadingRecoversAfterWholeEngineCacheIsRemoved() = runBlocking {
        assertEquals(2, engine.acquire(assetRequest).frames)
        removeCacheDirectory(engineDirectory)
        assertEquals(2, engine.acquire(assetRequest).frames)
        assertTrue(staging.isDirectory)
        assertEquals(2L, engine.decodeCount.get())
    }

    @Test fun cachedLoadingRecreatesStagingWithoutAnotherNetworkRequest() = runBlocking {
        val request = SvgaRequest(server.url("/cached.svga").toString()).copy(cachePolicy = SvgaCachePolicy.DISK)
        server.enqueue(response())
        assertEquals(2, engine.acquire(request).frames)
        removeCacheDirectory(staging)
        assertEquals(2, engine.acquire(request.copy(cacheOnly = true)).frames)
        assertTrue(staging.isDirectory)
        assertEquals(1, server.requestCount)
        assertEquals(1L, engine.diskHits.get())
    }

    @Test fun downloadRecreatesStagingWhenCacheIsRemovedWhileWaitingForResponse() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return response()
            }
        }
        val request = SvgaRequest(server.url("/download.svga").toString()).copy(cachePolicy = SvgaCachePolicy.NONE)
        val load = async(Dispatchers.Default) { engine.acquire(request) }
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS))
            removeCacheDirectory(engineDirectory)
            release.countDown()
            assertEquals(2, withTimeout(10_000) { load.await() }.frames)
            assertTrue(staging.isDirectory)
            assertEquals(1, server.requestCount)
        } finally {
            release.countDown()
            load.cancel()
        }
    }

    @Test fun concurrentLoadsCanRecreateTheSameDirectory() = runBlocking {
        removeCacheDirectory(engineDirectory)
        val resources = (0 until 8).map { index ->
            async(Dispatchers.Default) { engine.acquire(assetRequest.copy(namespace = "request-" + index)) }
        }.awaitAll()
        assertTrue(resources.all { it.frames == 2 })
        assertTrue(staging.isDirectory)
        assertEquals(8L, engine.decodeCount.get())
    }

    @Test fun fileAtStagingPathFailsClearlyAndCanBeRetriedAfterRemoval() = runBlocking {
        removeCacheDirectory(staging)
        staging.writeText("keep this file")
        val error = runCatching { engine.acquire(assetRequest) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains(staging.absolutePath))
        assertEquals("keep this file", staging.readText())
        assertTrue(staging.delete())
        assertEquals(2, engine.acquire(assetRequest).frames)
        assertTrue(staging.isDirectory)
    }
}
