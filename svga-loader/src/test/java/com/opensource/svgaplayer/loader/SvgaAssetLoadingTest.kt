package com.opensource.svgaplayer.loader

import android.content.ContextWrapper
import android.content.res.AssetManager
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SvgaAssetLoadingTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var assets: AssetManager
    private lateinit var context: ContextWrapper
    private lateinit var cache: File
    private lateinit var engine: SvgaEngine
    private val request get() = SvgaRequest(SvgaSource.Asset("gift.svga"), cachePolicy = SvgaCachePolicy.NONE)

    @Before fun setUp() {
        val data = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.spec"))
                zip.write("""{"movie":{"viewBox":{"width":100,"height":80},"fps":20,"frames":2},"images":{},"sprites":[]}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val apk = temporary.newFile("assets.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            for (path in listOf("gift.svga", "folder/demo gift.svga", "folder/礼物.svga", "folder/a+b.svga", "folder/a%20b.svga")) {
                zip.putNextEntry(ZipEntry("assets/$path"))
                zip.write(data)
                zip.closeEntry()
            }
        }
        assets = ReflectionHelpers.newInstance(AssetManager::class.java)
        assertTrue(ReflectionHelpers.callInstanceMethod<Int>(assets, "addAssetPath",
            ClassParameter.from(String::class.java, apk.absolutePath)) != 0)
        cache = temporary.newFolder("cache")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = cache
            override fun getAssets() = this@SvgaAssetLoadingTest.assets
        }
        engine = SvgaEngine(context, decodeConcurrency = 1)
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.close()
        if (::assets.isInitialized) assets.close()
    }

    private suspend fun awaitSourceSubscribers(count: Int) {
        val sources = ReflectionHelpers.getField<Any>(engine, "sources")
        val flights = ReflectionHelpers.getField<MutableMap<*, *>>(sources, "flights")
        withTimeout(10_000) {
            while (true) {
                if (synchronized(flights) {
                    flights.values.any { flight ->
                        ReflectionHelpers.getField<Any?>(flight, "result") != null &&
                            ReflectionHelpers.getField<Int>(flight, "users") == count
                    }
                }) return@withTimeout
                delay(5)
            }
        }
    }

    private fun removeEngineCache() {
        val directory = File(cache, "svga-engine")
        assertTrue(directory.canonicalFile.toPath().startsWith(cache.canonicalFile.toPath()))
        assertTrue(directory.deleteRecursively())
    }

    @Test fun assetSurvivesCacheRemovalWhileWaitingForDecode() = runBlocking {
        val decodes = ReflectionHelpers.getField<Semaphore>(engine, "decodes")
        decodes.acquire()
        val loading = async(Dispatchers.Default) { engine.acquire(request) }
        try {
            awaitSourceSubscribers(1)
            removeEngineCache()
        } finally { decodes.release() }
        assertEquals(2, withTimeout(10_000) { loading.await() }.frames)
        assertEquals(1L, engine.decodeCount.get())
    }

    @Test fun sharedAssetSurvivesCacheRemovalAndOneSubscriberCancelling() = runBlocking {
        val decodes = ReflectionHelpers.getField<Semaphore>(engine, "decodes")
        decodes.acquire()
        val cancelled = async(Dispatchers.Default) { engine.acquire(request.copy(width = 64, height = 64)) }
        val first = async(Dispatchers.Default) { engine.acquire(request.copy(width = 128, height = 128)) }
        val second = async(Dispatchers.Default) { engine.acquire(request.copy(width = 256, height = 256)) }
        try {
            awaitSourceSubscribers(3)
            cancelled.cancelAndJoin()
            removeEngineCache()
        } finally { decodes.release() }
        assertEquals(2, withTimeout(10_000) { first.await() }.frames)
        assertEquals(2, withTimeout(10_000) { second.await() }.frames)
        assertEquals(2L, engine.decodeCount.get())
    }

    @Test fun parsingPreDownloadSurvivesCacheRemovalWhileWaitingForDecode() = runBlocking {
        val decodes = ReflectionHelpers.getField<Semaphore>(engine, "decodes")
        decodes.acquire()
        val loading = async(Dispatchers.Default) { engine.preDownload(request, parseAfterDownload = true) }
        try {
            awaitSourceSubscribers(1)
            removeEngineCache()
        } finally { decodes.release() }
        withTimeout(10_000) { loading.await() }
        assertEquals(1L, engine.decodeCount.get())
    }

    @Test fun encodedAssetUrisLoadAndDecodeOnlyOnce() = runBlocking {
        for ((encoded, path) in listOf(
            "folder/demo%20gift.svga" to "folder/demo gift.svga",
            "folder/%E7%A4%BC%E7%89%A9.svga" to "folder/礼物.svga",
            "folder/a+b.svga" to "folder/a+b.svga",
            "folder/a%2Bb.svga" to "folder/a+b.svga",
            "folder/a%2520b.svga" to "folder/a%20b.svga"
        )) {
            val source = SvgaSource.from("file:///android_asset/$encoded")
            assertEquals(SvgaSource.Asset(path), source)
            assertEquals(2, engine.acquire(request.copy(source = source)).frames)
        }
        assertEquals(2, engine.acquire(request.copy(source = SvgaSource.Asset("folder/a%20b.svga"))).frames)
        assertEquals(2, engine.acquire(SvgaRequest("file:///android_asset/folder/demo gift.svga")).frames)
    }

    @Test fun assetUriQueryAndFragmentAreNotPartOfTheFilename() = runBlocking {
        val source = SvgaSource.from("file:///android_asset/gift.svga?version=2#preview")
        assertEquals(SvgaSource.Asset("gift.svga"), source)
        assertEquals(2, engine.acquire(request.copy(source = source)).frames)
    }

    @Test fun missingAssetStillFailsAndDoesNotPoisonLaterRequests() = runBlocking {
        val error = runCatching { engine.acquire(request.copy(source = SvgaSource.Asset("missing.svga"))) }.exceptionOrNull()
        assertTrue(error is FileNotFoundException)
        assertEquals(2, engine.acquire(request).frames)
    }

    @Test fun sharedEngineIsRecreatedOnceAfterCloseAndCanLoadAssets() = runBlocking {
        val previous = ReflectionHelpers.getStaticField<SvgaEngine?>(SvgaEngine::class.java, "shared")
        ReflectionHelpers.setStaticField(SvgaEngine::class.java, "shared", null)
        try {
            val first = SvgaEngine.get(context)
            assertEquals(2, first.acquire(request).frames)
            first.close()
            assertTrue(runCatching { first.acquire(request) }.exceptionOrNull() is IllegalStateException)
            val replacements = (0 until 8).map { async(Dispatchers.Default) { SvgaEngine.get(context) } }.awaitAll()
            val replacement = replacements.first()
            assertNotSame(first, replacement)
            assertTrue(replacements.all { it === replacement })
            // Repeated cleanup of an old owner must not discard the replacement singleton.
            first.close()
            assertSame(replacement, SvgaEngine.get(context))
            assertEquals(2, replacement.acquire(request).frames)
        } finally {
            ReflectionHelpers.getStaticField<SvgaEngine?>(SvgaEngine::class.java, "shared")?.close()
            ReflectionHelpers.setStaticField(SvgaEngine::class.java, "shared", previous)
        }
    }
}
