package com.opensource.svgaplayer.loader

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
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
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.MovieParams
import com.opensource.svgaplayer.proto.FrameEntity
import com.opensource.svgaplayer.proto.Layout
import com.opensource.svgaplayer.proto.SpriteEntity
import com.opensource.svgaplayer.proto.Transform

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SvgaZipCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var engine: SvgaEngine
    private lateinit var server: MockWebServer
    private lateinit var assets: AssetManager
    private lateinit var apk: File
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getCacheDir() = temporary.root
            override fun getAssets() = this@SvgaZipCacheTest.assets
            override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
                sourceDir = apk.absolutePath; splitSourceDirs = null
            }
        }
    }
    private val root get() = File(temporary.root, "svga-engine")
    private fun expanded() = File(root, "unzipped").listFiles().orEmpty().filter { it.isDirectory }
    private fun bodies() = File(root, "sources").listFiles().orEmpty().filter { !it.name.endsWith(".tmp") }
    private fun request(path: String = "/gift.svga") = SvgaRequest(server.url(path).toString()).copy(cachePolicy = SvgaCachePolicy.DISK)
    private fun asset() = SvgaRequest(SvgaSource.Asset("gift.svga"), cachePolicy = SvgaCachePolicy.DISK)
    private fun response(bytes: ByteArray = zipMovie(), cache: String = "max-age=3600") =
        MockResponse().setHeader("Cache-Control", cache).setBody(Buffer().write(bytes))

    private fun redPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun imageMovie(png: ByteArray): ByteArray {
        val frame = FrameEntity(1f, Layout(0f, 0f, 16f, 16f), Transform(1f, 0f, 0f, 1f, 0f, 0f), null, emptyList())
        val movie = MovieEntity("2.0", MovieParams(16f, 16f, 20, 2),
            mapOf("base" to "base.png".toByteArray().toByteString()),
            listOf(SpriteEntity("base", listOf(frame, frame), null)), emptyList())
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("movie.binary")); zip.write(MovieEntity.ADAPTER.encode(movie)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("base.png")); zip.write(png); zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun corruptPngPixels(png: ByteArray): ByteArray {
        val damaged = png.copyOf()
        var offset = 8
        while (offset + 12 <= damaged.size) {
            val length = ByteBuffer.wrap(damaged, offset, 4).int
            if (String(damaged, offset + 4, 4, Charsets.US_ASCII) == "IDAT") {
                // Keep the PNG header and file length intact, but invalidate the compressed pixel stream.
                require(length >= 2)
                damaged[offset + 8] = 0; damaged[offset + 9] = 0
                return damaged
            }
            offset += length + 12
        }
        error("Missing PNG pixel data")
    }

    private fun firstFrameColor(resource: SvgaResource): Int {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            resource.newRenderer().use { it.prepare(16, 16); it.draw(Canvas(bitmap), 16, 16, 0, 0) }
            bitmap.getPixel(8, 8)
        } finally { bitmap.recycle() }
    }

    private suspend fun awaitImageFailureInvalidation(entry: File) = withTimeout(10_000) {
        // Observe the whole asynchronous invalidation transaction before checking the original body.
        while (ZipDirectoryCache.mutex.withLock { entry.exists() }) delay(5)
    }

    @Before fun setUp() {
        apk = temporary.newFile("assets.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/gift.svga")); zip.write(zipMovie()); zip.closeEntry()
        }
        assets = ReflectionHelpers.newInstance(AssetManager::class.java)
        assertTrue(ReflectionHelpers.callInstanceMethod<Int>(assets, "addAssetPath", ClassParameter.from(String::class.java, apk.absolutePath)) != 0)
        server = MockWebServer().apply { start() }
        engine = SvgaEngine(context)
    }
    @After fun tearDown() { engine.close(); server.shutdown(); assets.close() }

    @Test fun remoteReusesDirectoryAfterMemoryClearAndAcrossEngines() = runBlocking {
        val request = request()
        server.enqueue(response())
        assertEquals(2, engine.acquire(request).frames)
        engine.clearMemory()
        assertEquals(2, engine.acquire(request.copy(width = 128, height = 128)).frames)
        assertEquals(1L, engine.zipExtractCount.get()); assertEquals(1L, engine.zipCacheHits.get())
        engine.close(); engine = SvgaEngine(context)
        assertEquals(2, engine.acquire(request.copy(cacheOnly = true)).frames)
        assertEquals(0L, engine.zipExtractCount.get()); assertEquals(1L, engine.zipCacheHits.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun assetsAndBinaryLocalFilesReuseDirectoriesButChangedFilesDoNot() = runBlocking {
        assertEquals(2, engine.acquire(asset()).frames)
        assertEquals(2, engine.acquire(asset().copy(width = 64, progressive = true)).frames)
        val file = temporary.newFile("local.svga").apply { writeBytes(zipMovie(binary = true)) }
        val local = SvgaRequest(file).copy(cachePolicy = SvgaCachePolicy.DISK)
        assertEquals(2, engine.acquire(local).frames); assertEquals(2, engine.acquire(local).frames)
        file.writeBytes(zipMovie(frames = 3, binary = true))
        assertEquals(3, engine.acquire(local).frames)
        assertEquals(3L, engine.zipExtractCount.get()); assertEquals(2L, engine.zipCacheHits.get())
        assertTrue(bodies().isEmpty())
    }

    @Test fun changedPackageStampInvalidatesAssetDirectoriesAcrossEngines() = runBlocking {
        engine.acquire(asset())
        val previous = checkNotNull(installedAssetVersion(context))
        engine.close()
        assertTrue(apk.setLastModified(apk.lastModified() + 10_000))
        assertNotEquals(previous, installedAssetVersion(context))
        engine = SvgaEngine(context)
        engine.acquire(asset())
        assertEquals(1L, engine.zipExtractCount.get()); assertEquals(0L, engine.zipCacheHits.get())
    }

    @Test fun compatibleDifferentSizesAndMultipleEnginesShareOneExtraction() = runBlocking {
        server.enqueue(response()); server.enqueue(response())
        val other = SvgaEngine(context)
        try {
            val request = request()
            val jobs = (1..6).map { size -> async(Dispatchers.Default) {
                (if (size % 2 == 0) other else engine).acquire(request.copy(width = size * 64, height = size * 64))
            } }
            assertTrue(withTimeout(15_000) { jobs.awaitAll() }.all { it.frames == 2 })
            assertEquals(1L, engine.zipExtractCount.get() + other.zipExtractCount.get())
            assertEquals(5L, engine.zipCacheHits.get() + other.zipCacheHits.get())
        } finally { other.close() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun concurrentMemoryAndDiskSubscribersCompleteBeforeEviction() = runBlocking {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/rose_2.0.0.svga")).use { it.readBytes() }
        server.enqueue(response(bytes))
        val request = request().copy(width = 256, height = 256, cachePolicy = SvgaCachePolicy.ALL)
        withTimeout(15_000) {
            val resources = (0 until 10).map { async { engine.acquire(request) } }.awaitAll()
            assertTrue(resources.all { it === resources.first() })
            assertEquals(1L, engine.decodeCount.get()); assertEquals(1, server.requestCount)
            assertSame(resources.first(), engine.acquire(request))
            engine.clearMemory(); engine.clearDisk()
            assertTrue(resources.first().sizeBytes > 0)
        }
    }

    @Test fun diskOverridesRefreshAndNoneAreRespected() = runBlocking {
        engine.acquire(asset())
        engine.acquire(asset().copy(diskRead = false))
        engine.acquire(asset().copy(refresh = true))
        engine.acquire(asset().copy(cachePolicy = SvgaCachePolicy.NONE))
        engine.acquire(asset().copy(diskWrite = false))
        assertEquals(4L, engine.zipExtractCount.get()); assertEquals(1L, engine.zipCacheHits.get())
        engine.clearDisk()
        engine.acquire(asset().copy(diskWrite = false))
        assertTrue(expanded().isEmpty())
        engine.acquire(asset().copy(cachePolicy = SvgaCachePolicy.NONE, diskWrite = true))
        assertEquals(1, expanded().size)
    }

    @Test fun namespaceAndHeadersAreIsolated() = runBlocking {
        val request = asset()
        engine.acquire(request)
        engine.acquire(request.copy(namespace = "other"))
        engine.acquire(request.copy(headers = mapOf("Authorization" to "secret")))
        assertEquals(3L, engine.zipExtractCount.get()); assertEquals(3, expanded().size)
        assertTrue(expanded().all { !File(it, "metadata.json").readText().contains("secret") })
    }

    @Test fun conditional304KeepsDirectoryAndChangedBodyUsesNewDigest() = runBlocking {
        val request = request()
        server.enqueue(response(cache = "max-age=0").setHeader("ETag", "v1"))
        engine.acquire(request)
        val generation = JSONObject(File(expanded().single(), "metadata.json").readText()).getString("generation")
        server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=0"))
        engine.acquire(request)
        assertEquals(generation, JSONObject(File(expanded().single(), "metadata.json").readText()).getString("generation"))
        server.takeRequest(); assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
        server.enqueue(response(zipMovie(frames = 3)))
        assertEquals(3, engine.acquire(request).frames)
        assertEquals(1, expanded().size)
        assertEquals(2L, engine.zipExtractCount.get()); assertEquals(1L, engine.zipCacheHits.get())
    }

    @Test fun staleDirectoryRequiresOptInAndNoStoreInvalidatesOldDirectories() = runBlocking {
        val request = request()
        server.enqueue(response(cache = "max-age=0")); engine.acquire(request)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { engine.acquire(request) }.isFailure)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(2, engine.acquire(request.copy(allowStaleOnError = true)).frames)
        assertEquals(1L, engine.zipCacheHits.get())
        server.enqueue(response(cache = "no-store")); engine.acquire(request.copy(refresh = true))
        assertTrue(expanded().isEmpty()); assertTrue(bodies().isEmpty())
        assertTrue(runCatching { engine.acquire(request.copy(cacheOnly = true)) }.isFailure)
    }

    @Test fun noStoreVaryAndDisabledDiskNeverPublishDirectories() = runBlocking {
        for ((index, result) in listOf(response(cache = "no-store"), response().setHeader("Vary", "*"), response()).withIndex()) {
            server.enqueue(result)
            engine.acquire(request("/$index.svga").copy(diskWrite = index != 2))
        }
        assertTrue(expanded().isEmpty()); assertTrue(bodies().isEmpty())
        engine.close(); engine = SvgaEngine(context, diskBytes = 0)
        engine.acquire(asset()); assertTrue(expanded().isEmpty())
    }

    @Test fun onlyDownloadDoesNotExpandButParsingDownloadWarmsDirectory() = runBlocking {
        val request = request()
        server.enqueue(response()); engine.preDownload(request)
        assertTrue(expanded().isEmpty()); assertEquals(0L, engine.zipExtractCount.get())
        engine.preDownload(request, parseAfterDownload = true)
        assertEquals(1L, engine.zipExtractCount.get()); assertEquals(1, expanded().size)
        engine.acquire(request.copy(cacheOnly = true))
        assertEquals(1L, engine.zipCacheHits.get()); assertEquals(1, server.requestCount)
    }

    @Test fun deletedOrSameLengthCorruptFilesRecoverOnceWithoutRejectingTheBody() = runBlocking {
        val request = request()
        server.enqueue(response()); engine.acquire(request)
        val file = File(expanded().single(), "content/movie.spec")
        file.writeText("!".repeat(file.length().toInt()))
        assertEquals(2, engine.acquire(request.copy(cacheOnly = true)).frames)
        assertEquals(2L, engine.zipExtractCount.get()); assertEquals(2L, engine.decodeCount.get())
        assertEquals(1, bodies().size); assertEquals(1, server.requestCount)
        File(expanded().single(), "content/movie.spec").delete()
        assertEquals(2, engine.acquire(request.copy(cacheOnly = true)).frames)
        assertEquals(3L, engine.zipExtractCount.get()); assertEquals(1, server.requestCount)
    }

    @Test fun readonlyCorruptionRecoveryUsesPrivateFiles() = runBlocking {
        val request = request()
        server.enqueue(response()); engine.acquire(request)
        val metadata = File(expanded().single(), "metadata.json").readText()
        val file = File(expanded().single(), "content/movie.spec")
        val broken = "!".repeat(file.length().toInt()); file.writeText(broken)
        assertEquals(2, engine.acquire(request.copy(cacheOnly = true, diskWrite = false)).frames)
        assertEquals(broken, file.readText())
        assertEquals(metadata, File(expanded().single(), "metadata.json").readText())
        assertEquals(1, bodies().size)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lazyDirectoryImageFailurePreservesOriginalForOfflineRebuild() = runBlocking {
        val png = redPng()
        val bytes = imageMovie(png)
        val request = request().copy(cachePolicy = SvgaCachePolicy.ALL, progressive = true)
        server.enqueue(response(bytes)); engine.acquire(request)
        val entry = expanded().single()
        val generation = JSONObject(File(entry, "metadata.json").readText()).getString("generation")
        val body = File(bodies().single(), "body")
        val image = File(entry, "content/base.png")
        image.writeBytes(corruptPngPixels(image.readBytes()))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        assertEquals(16, bounds.outWidth); assertEquals(png.size.toLong(), image.length())
        engine.clearMemory()

        val broken = engine.acquire(request.copy(cacheOnly = true))
        assertEquals(1L, engine.zipCacheHits.get())
        assertTrue(runCatching { firstFrameColor(broken) }.isFailure)
        assertTrue(broken.hasImageDecodeError)
        awaitImageFailureInvalidation(entry)
        assertArrayEquals(bytes, body.readBytes())

        val rebuilt = withTimeout(10_000) { engine.acquire(request.copy(cacheOnly = true)) }
        assertNotSame(broken, rebuilt)
        assertEquals(Color.RED, firstFrameColor(rebuilt)); assertFalse(rebuilt.hasImageDecodeError)
        val replacement = expanded().single()
        assertNotEquals(generation, JSONObject(File(replacement, "metadata.json").readText()).getString("generation"))
        assertArrayEquals(png, File(replacement, "content/base.png").readBytes())
        assertArrayEquals(bytes, body.readBytes())
        assertEquals(2L, engine.zipExtractCount.get())
        engine.clearMemory()
        assertEquals(Color.RED, firstFrameColor(engine.acquire(request.copy(cacheOnly = true))))
        assertEquals(2L, engine.zipCacheHits.get()); assertEquals(2L, engine.zipExtractCount.get())
        assertEquals(1, server.requestCount)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lazyImageFailureFromOriginalStillInvalidatesBody() = runBlocking {
        val png = redPng()
        val request = request().copy(cachePolicy = SvgaCachePolicy.ALL, progressive = true)
        server.enqueue(response(imageMovie(corruptPngPixels(png))))
        val broken = engine.acquire(request)
        val entry = expanded().single()
        assertTrue(runCatching { firstFrameColor(broken) }.isFailure)
        awaitImageFailureInvalidation(entry)
        assertTrue(bodies().isEmpty()); assertTrue(expanded().isEmpty())
        assertTrue(runCatching { engine.acquire(request.copy(cacheOnly = true)) }.isFailure)

        server.enqueue(response(imageMovie(png)))
        assertEquals(Color.RED, firstFrameColor(engine.acquire(request)))
        assertEquals(2, server.requestCount)
    }

    @Test fun missingDirectoryRebuildsLocallyButOrphanDirectoryIsNotARemoteSource() = runBlocking {
        val request = request()
        server.enqueue(response()); engine.acquire(request)
        assertTrue(expanded().single().deleteRecursively())
        engine.acquire(request.copy(cacheOnly = true))
        assertEquals(2L, engine.zipExtractCount.get()); assertEquals(1, server.requestCount)
        assertTrue(bodies().single().deleteRecursively())
        assertTrue(runCatching { engine.acquire(request.copy(cacheOnly = true)) }.isFailure)
    }

    @Test fun clearDuringCompletedSourceFlightPreventsLateBodyAndDirectoryPublication() = runBlocking {
        val completed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        server.enqueue(response())
        val job = async { engine.acquire(request()) { if (it.completed) { completed.complete(Unit); resume.await() } } }
        try {
            withTimeout(10_000) { completed.await() }
            engine.clearDisk(); resume.complete(Unit)
            assertEquals(2, withTimeout(10_000) { job.await() }.frames)
            assertTrue(expanded().isEmpty()); assertTrue(bodies().isEmpty())
        } finally { resume.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun lateOlderSourceCannotReplaceNewerBodyOrExpandedVersion() = runBlocking {
        val completed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val request = request().copy(cachePolicy = SvgaCachePolicy.ALL)
        server.enqueue(response())
        val older = async { engine.acquire(request.copy(diskRead = false)) {
            if (it.completed) { completed.complete(Unit); resume.await() }
        } }
        try {
            withTimeout(10_000) { completed.await() }
            server.enqueue(response(zipMovie(frames = 3)))
            assertEquals(3, engine.acquire(request.copy(refresh = true)).frames)
            resume.complete(Unit)
            assertEquals(2, older.await().frames)
            assertEquals(3, engine.acquire(request.copy(cacheOnly = true)).frames)
            engine.clearMemory()
            assertEquals(3, engine.acquire(request.copy(cacheOnly = true)).frames)
            assertEquals(1, expanded().size); assertEquals(2, server.requestCount)
        } finally { resume.complete(Unit); older.cancelAndJoin() }
    }

    @Test fun expandedBudgetKeepsSmallOriginalAndSkipsLargeDirectory() = runBlocking {
        engine.close(); engine = SvgaEngine(context, diskBytes = 20_000)
        val request = request()
        server.enqueue(response(zipMovie(padding = 1024 * 1024)))
        engine.acquire(request); engine.acquire(request.copy(cacheOnly = true))
        assertTrue(expanded().isEmpty()); assertEquals(1, bodies().size)
        assertEquals(2L, engine.zipExtractCount.get()); assertEquals(1, server.requestCount)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pixelBudgetFailureDoesNotReextractOrInvalidateIntactDirectory() = runBlocking {
        val binary = InflaterInputStream(checkNotNull(javaClass.getResourceAsStream("/rose_2.0.0.svga"))).use { it.readBytes() }
        val request = request().copy(width = 128, height = 128)
        server.enqueue(response(zipBinaryMovie(binary)))
        engine.acquire(request)
        val metadata = File(expanded().single(), "metadata.json").readText()
        engine.close(); engine = SvgaEngine(context, maxDecodedBytes = 1)
        val error = runCatching { engine.acquire(request.copy(cacheOnly = true)) }.exceptionOrNull()
        assertTrue(error is SvgaResource.DecodeBudgetExceededException)
        assertEquals(0L, engine.zipExtractCount.get())
        assertEquals(metadata, File(expanded().single(), "metadata.json").readText())
        assertEquals(1, bodies().size); assertEquals(1, server.requestCount)
    }

    @Test fun cancellingAllResourceSubscribersRemovesCandidateAndReleasesBuildLock() = runBlocking {
        engine.close(); engine = SvgaEngine(context, decodeConcurrency = 1)
        val decodes = ReflectionHelpers.getField<Semaphore>(engine, "decodes")
        decodes.acquire()
        val job = async(Dispatchers.Default) { engine.acquire(asset()) }
        try {
            withTimeout(10_000) {
                while (File(root, "staging").listFiles().orEmpty().none { it.name.startsWith("zip-") }) delay(5)
            }
            job.cancelAndJoin()
        } finally { decodes.release(); job.cancelAndJoin() }
        withTimeout(10_000) {
            while (File(root, "staging").listFiles().orEmpty().any { it.name.startsWith("zip-") }) delay(5)
        }
        assertTrue(expanded().isEmpty())
        assertEquals(2, withTimeout(10_000) { engine.acquire(asset()) }.frames)
        assertEquals(1L, engine.zipExtractCount.get())
    }

    @Test fun cancellingOneSourceSubscriberPreservesSurvivorsDirectory() = runBlocking {
        val completed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val request = request()
        server.enqueue(response())
        val first = async { engine.acquire(request) { if (it.completed) { completed.complete(Unit); resume.await() } } }
        try {
            withTimeout(10_000) { completed.await() }
            val survivor = async(start = CoroutineStart.UNDISPATCHED) { engine.acquire(request.copy(width = 128)) }
            first.cancelAndJoin()
            assertEquals(2, withTimeout(10_000) { survivor.await() }.frames)
            engine.acquire(request.copy(cacheOnly = true))
            assertEquals(1L, engine.zipExtractCount.get()); assertEquals(1L, engine.zipCacheHits.get())
        } finally { resume.complete(Unit); first.cancelAndJoin() }
    }

    @Test fun lruEvictsSourceAndDirectoryTogetherAndCountsExpandedBytes() = runBlocking {
        val first = request("/first.svga"); val second = request("/second.svga")
        server.enqueue(response()); engine.acquire(first)
        val firstDirectory = expanded().single()
        val firstIdentity = JSONObject(File(firstDirectory, "metadata.json").readText()).getString("sourceIdentity")
        server.enqueue(response()); engine.acquire(second)
        val limit = root.walkTopDown().filter { it.isFile }.sumOf { it.length() } + 128
        // Make the first source unambiguously least recently used without wall-clock sleeps.
        expanded().forEach { it.setLastModified(if (it == firstDirectory) 1 else 2) }
        bodies().forEach { it.setLastModified(if (it.name == firstIdentity) 1 else 2) }
        engine.close(); engine = SvgaEngine(context, diskBytes = limit)
        server.enqueue(response()); engine.acquire(request("/third.svga"))
        assertFalse(firstDirectory.exists())
        assertFalse(File(root, "sources/$firstIdentity").exists())
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= limit)
        assertEquals(2, expanded().size)
    }

    @Test fun unwriteablePersistentPathDoesNotBreakSuccessfulDecode() = runBlocking {
        val blocked = File(root, "unzipped").apply { writeText("keep") }
        engine.acquire(asset())
        assertEquals("keep", blocked.readText())
        assertEquals(1L, engine.zipExtractCount.get())
    }

    @Test fun fileAtEntryPathIsPreservedWhileDecodeStillSucceeds() = runBlocking {
        engine.acquire(asset())
        val entry = expanded().single()
        assertTrue(entry.deleteRecursively()); entry.writeText("keep")
        assertEquals(2, engine.acquire(asset()).frames)
        assertEquals("keep", entry.readText())
    }

    @Test fun unsafeOrIncompleteArchivesNeverPublishReadyDirectories() = runBlocking {
        for (entry in listOf("../.complete", "image-without-movie.png")) {
            val bytes = java.io.ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(entry)); zip.write(byteArrayOf(1)); zip.closeEntry()
                }
            }.toByteArray()
            server.enqueue(response(bytes))
            assertTrue(runCatching { engine.acquire(request("/${server.requestCount}.svga")) }.isFailure)
            assertTrue(expanded().isEmpty()); assertTrue(bodies().isEmpty())
            assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun zlibDoesNotCreateAnExpandedDirectory() = runBlocking {
        val file = temporary.newFile("zlib.svga")
        DeflaterOutputStream(file.outputStream()).use {
            it.write(MovieEntity.ADAPTER.encode(MovieEntity("2.0", MovieParams(100f, 80f, 20, 2), emptyMap(), emptyList(), emptyList())))
        }
        repeat(2) { assertEquals(2, engine.acquire(SvgaRequest(file).copy(cachePolicy = SvgaCachePolicy.DISK)).frames) }
        assertTrue(expanded().isEmpty()); assertEquals(0L, engine.zipExtractCount.get())
    }
}
