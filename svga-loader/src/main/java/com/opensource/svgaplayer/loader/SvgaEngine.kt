package com.opensource.svgaplayer.loader

import android.content.Context
import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SvgaEngine(
    context: Context,
    val memoryBytes: Long = 32L * 1024 * 1024,
    val diskBytes: Long = 128L * 1024 * 1024,
    client: OkHttpClient = OkHttpClient(),
    downloadConcurrency: Int = 4,
    decodeConcurrency: Int = 2,
    val maxDecodedBytes: Long = 256L * 1024 * 1024,
    val weakMemoryCacheEnabled: Boolean = true,
    preDownloadConcurrency: Int = 2,
) : AutoCloseable {
    init {
        require(memoryBytes >= 0 && diskBytes >= 0 && maxDecodedBytes > 0)
        require(downloadConcurrency > 0 && decodeConcurrency > 0 && preDownloadConcurrency > 0)
    }
    private val context = context.applicationContext
    private val root = File(context.cacheDir, "svga-engine").apply { mkdirs() }
    private val staging = File(root, "staging").apply { mkdirs() }
    private val disk = File(root, "sources").apply { mkdirs() }
    private val zipCache = ZipDirectoryCache(root, diskBytes)
    private val assetVersion = installedAssetVersion(this.context)
    private val client = client.newBuilder().cache(null).callTimeout(45, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = Semaphore(downloadConcurrency)
    private val preDownloads = Semaphore(preDownloadConcurrency)
    private val decodes = Semaphore(decodeConcurrency)
    private data class Source(val open: () -> InputStream, val version: String, val reusable: Boolean,
        val dispose: () -> Unit = {},
        val expiresAt: Long = Long.MAX_VALUE, val publish: suspend (Boolean) -> Unit = {},
        val reject: suspend () -> Unit = {}, val epoch: Long = 0,
        val cacheMemory: suspend (() -> Unit) -> Unit = { it() })
    private class SourceRejections {
        var users = 0 // Guarded by sourceRejections.
        val digests = HashSet<String>() // Guarded by diskMutex.
    }
    private val sourceRejections = HashMap<String, SourceRejections>()
    private val sources = SharedFlights<String, Source>(scope) { it.dispose() }
    private val resources = SharedFlights<String, SvgaResource>(scope)
    private val memory = ResourceMemoryCache<SvgaResource>(memoryBytes, { !it.hasImageDecodeError }) { it.sizeBytes }
    val weakMemoryHits = AtomicLong()
    val networkRequests = AtomicLong()
    val decodeCount = AtomicLong()
    val memoryHits = AtomicLong()
    val diskHits = AtomicLong()
    val zipCacheHits = AtomicLong()
    val zipExtractCount = AtomicLong()
    private val partials = ResumableDownload(File(root, "partials").apply { mkdirs() }, staging, this.client,
        minOf(diskBytes, SvgaResource.MAX_INPUT_BYTES)) { networkRequests.incrementAndGet() }

    init {
        scope.launch { diskMutex.withLock {
            val abandonedBefore = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
            staging.listFiles().orEmpty().filter { it.lastModified() < abandonedBefore && !zipCache.isActiveStagingLocked(it) }
                .forEach { it.deleteRecursively() }
            disk.listFiles().orEmpty().filter { it.name.endsWith(".tmp") && it.lastModified() < abandonedBefore }.forEach { it.deleteRecursively() }
            zipCache.pruneLocked()
        }; partials.prune() }
    }

    suspend fun acquire(request: SvgaRequest, onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = request.onDownloadProgress): SvgaResource {
        val r = decodeSnapshot(request)
        check(scope.isActive) { "Engine closed" }
        val lookupKey = "${sourceIdentity(r)}:${decodeKey(r)}"
        val weakEnabled = weakMemoryCacheEnabled && r.usesWeakMemory
        if (r.refresh) memory.invalidate(sourceIdentity(r) + ":")
        if (!r.refresh) memory.find(lookupKey, r.readsMemory, weakEnabled, r.cacheOnly)?.let { hit ->
            if (hit.weak) weakMemoryHits.incrementAndGet() else memoryHits.incrementAndGet()
            memory.put(hit.key, lookupKey, hit.expires, hit.value, r.writesMemory, weakEnabled)
            return hit.value
        }
        return useSource(r, onDownloadProgress, preDownload = false) { prepare(r, it) }
    }

    /**
     * Download the original body without decoding by default. Disk writes follow [SvgaRequest.writesDisk]
     * and HTTP cache rules. When [parseAfterDownload] is true, also prepare the resource and apply the
     * request's strong/weak memory cache policy. No View or playback instance is created.
     * Compatible foreground requests share the same source flight and cancel independently.
     */
    suspend fun preDownload(request: SvgaRequest, parseAfterDownload: Boolean = false,
        onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = request.onDownloadProgress) {
        val r = if (parseAfterDownload) decodeSnapshot(request) else request.snapshot()
        check(scope.isActive) { "Engine closed" }
        if (r.refresh) memory.invalidate(sourceIdentity(r) + ":")
        useSource(r, onDownloadProgress, preDownload = true) { source ->
            if (parseAfterDownload) prepare(r, source)
            else withContext(Dispatchers.IO) { source.publish(false) }
        }
    }

    private fun decodeSnapshot(request: SvgaRequest): SvgaRequest {
        val r = request.snapshot()
        val options = r.resolveDecodeOptions()
        return r.copy(bitmapConfig = options.bitmapConfig, skipInvisibleImages = options.skipInvisibleImages,
            renderScale = options.renderScale)
    }

    private fun decodeKey(r: SvgaRequest): String {
        val options = r.resolveDecodeOptions()
        return "${r.width}:${r.height}:${options.bitmapConfig.name}:${options.skipInvisibleImages}:${options.inBitmap}:${options.progressive}:${options.renderScale}"
    }

    private suspend fun <T> useSource(r: SvgaRequest, onProgress: (suspend (SvgaDownloadProgress) -> Unit)?,
        preDownload: Boolean, consume: suspend (Source) -> T): T {
        val identity = sourceIdentity(r)
        val epoch = zipCache.epoch()
        // Register before downloading so a late response also sees overlapping decode failures.
        val rejections = synchronized(sourceRejections) {
            sourceRejections.getOrPut(identity) { SourceRejections() }.also { it.users++ }
        }
        try {
            return sources.useWithProgress("${sourceKey(r)}:$epoch", onProgress, { progress ->
                // Limit producers, not subscribers: duplicates never consume another download slot.
                if (preDownload) preDownloads.withPermit { downloads.withPermit { source(r, progress, rejections, epoch) } }
                else downloads.withPermit { source(r, progress, rejections, epoch) }
            }, consume)
        } finally {
            synchronized(sourceRejections) {
                // Once all overlapping requests end, later transfers may retry any body.
                if (--rejections.users == 0) sourceRejections.remove(identity)
            }
        }
    }

    private suspend fun prepare(r: SvgaRequest, source: Source): SvgaResource {
        val decodeKey = decodeKey(r)
        val identity = sourceIdentity(r)
        val digest = source.version
        val lookupKey = "$identity:$decodeKey"
        val weakEnabled = weakMemoryCacheEnabled && r.usesWeakMemory
        return withContext(Dispatchers.IO) {
            val key = "$identity:$digest:$decodeKey"
            val hit = if (source.reusable && !r.refresh) memory.findKey(key, r.readsMemory, weakEnabled) else null
            if (hit != null) {
                if (hit.weak) weakMemoryHits.incrementAndGet() else memoryHits.incrementAndGet()
                source.publish(true)
                source.cacheMemory { memory.put(key, lookupKey, source.expiresAt, hit.value, r.writesMemory, weakEnabled) }
                hit.value
            } else {
                resources.use("$key:${sourceKey(r)}:${source.epoch}", {
                    decodeResource(r, source, identity)
                }, { resource ->
                    // Publish each subscriber's cache policy before releasing its shared flight.
                    if (source.reusable) source.cacheMemory {
                        memory.put(key, lookupKey, source.expiresAt, resource, r.writesMemory, weakEnabled)
                    }
                    resource
                })
            }
        }
    }

    private fun zipIdentity(r: SvgaRequest, source: Source, identity: String): ZipDirectoryCache.Identity? {
        val kind = when (r.source) {
            is SvgaSource.Remote -> "remote"
            is SvgaSource.LocalFile -> "file"
            is SvgaSource.Asset -> "asset"
        }
        val version = if (kind == "asset") assetVersion ?: return null else source.version
        return ZipDirectoryCache.Identity(identity, version, kind)
    }

    private suspend fun decodeResource(r: SvgaRequest, source: Source, identity: String): SvgaResource {
        var counted = false
        fun started() { if (!counted) { counted = true; decodeCount.incrementAndGet() } }
        val zipIdentity = zipIdentity(r, source, identity)
        val generation = AtomicReference<String?>()
        fun onImageError(fromDirectory: Boolean) = imageFailureCallback(identity, source.version, source.epoch,
            zipIdentity, generation, r.writesDisk, fromDirectory)
        val canRead = source.reusable && r.readsDisk && !r.refresh && zipIdentity != null
        val canWrite = source.reusable && r.writesDisk && diskBytes > 0 && zipIdentity != null
        suspend fun cached(): SvgaResource? {
            if (!canRead) return null
            val lease = zipCache.acquire(checkNotNull(zipIdentity)) ?: return null
            generation.set(lease.entry.generation)
            try {
                val resource = decodes.withPermit {
                    currentCoroutineContext().ensureActive()
                    started()
                    SvgaResource.decodeFromDirectory(lease.entry.content, r.width, r.height, maxDecodedBytes,
                        r.resolveDecodeOptions(), onImageError(fromDirectory = true))
                }
                source.publish(true)
                zipCacheHits.incrementAndGet()
                return resource
            } catch (e: SvgaResource.DirectoryDecodeException) {
                diskMutex.withLock { zipCache.invalidateLocked(lease.entry, r.writesDisk) }
                return null
            } finally { zipCache.release(lease) }
        }
        cached()?.let { return it }
        val lock = if (canWrite) zipCache.buildLock(checkNotNull(zipIdentity)) else null
        lock?.lock()
        var locked = lock != null
        try {
            // Only normal readable requests may consume another specification's newly published ZIP.
            cached()?.let { return it }
            val candidate = zipCache.createCandidate()
            generation.set(candidate.generation)
            try {
                var zip = false
                val resource = try {
                    decodes.withPermit {
                        currentCoroutineContext().ensureActive()
                        started()
                        source.open().buffered().use { input ->
                            input.mark(4)
                            zip = input.read() == 0x50 && input.read() == 0x4b
                            input.reset()
                            if (zip) zipExtractCount.incrementAndGet()
                            else if (locked) { lock!!.unlock(); locked = false }
                            SvgaResource.decode(input, candidate.content, r.width, r.height, maxDecodedBytes,
                                r.resolveDecodeOptions(), onImageError(fromDirectory = false))
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException && e !is SvgaResource.DecodeBudgetExceededException) source.reject()
                    throw e
                }
                source.publish(true)
                if (zip && canWrite) {
                    zipCache.publish(candidate, checkNotNull(zipIdentity), source.epoch)?.let { generation.set(it.generation) }
                }
                return resource
            } finally { zipCache.discard(candidate) }
        } finally { if (locked) lock!!.unlock() }
    }

    private fun imageFailureCallback(identity: String, digest: String, epoch: Long,
        zipIdentity: ZipDirectoryCache.Identity?, generation: AtomicReference<String?>, writesDisk: Boolean,
        fromDirectory: Boolean): () -> Unit = {
        // Do not retain requests, sources, or UI callbacks in a long-lived resource.
        memory.invalidate("$identity:$digest:")
        scope.launch {
            diskMutex.withLock {
                if (epoch != zipCache.epochLocked) return@withLock
                val expanded = zipIdentity?.let { zipCache.currentLocked(it) }
                if (expanded != null && expanded.generation != generation.get()) return@withLock
                if (expanded != null) zipCache.invalidateLocked(expanded, writesDisk)
                // A directory-only failure does not prove that the original body is corrupt.
                if (writesDisk && !fromDirectory) {
                    val entry = File(disk, identity)
                    val metadata = runCatching { JSONObject(File(entry, "metadata").readText()) }.getOrNull()
                    if (metadata?.optString("digest") == digest) entry.deleteRecursively()
                }
            }
        }
    }

    suspend fun preload(request: SvgaRequest) { acquire(request) }
    fun clearMemory() = memory.clear()
    val isClosed: Boolean get() = !scope.isActive
    override fun close() {
        scope.cancel(); clearMemory()
        synchronized(Companion) { if (shared === this) shared = null }
    }

    private fun sourceIdentity(r: SvgaRequest): String {
        val identity = when (val source = r.source) {
            is SvgaSource.LocalFile -> "${source.file.canonicalPath}:${source.file.length()}:${source.file.lastModified()}:${source.version}"
            else -> source.toString()
        }
        val parts = listOf(identity, r.namespace) + r.headers.flatMap { listOf(it.key, it.value) }
        return hash(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
    }
    private fun sourceKey(r: SvgaRequest) = "${sourceIdentity(r)}:${r.readsDisk}:${r.writesDisk}:${r.cacheOnly}:${r.refresh}:${r.allowStaleOnError}:${r.resumeDownloads}"

    private suspend fun source(r: SvgaRequest, progress: (SvgaDownloadProgress) -> Unit,
        rejections: SourceRejections, epoch: Long): Source {
        when (val source = r.source) {
            is SvgaSource.LocalFile -> return Source(source.file::inputStream, digest(source.file), true, epoch = epoch)
            is SvgaSource.Asset -> {
                // Packaged assets are fixed for this engine and sourceIdentity already includes the path.
                // Use that identity so decoding does not require a preliminary content-hash scan.
                val open = { openAsset(source.path) }
                val validation = Mutex()
                var validated = false
                return Source(open, "asset", true, publish = { parsed ->
                    // Download-only callers still validate existence, input size and cancellation.
                    // Overlapping subscribers validate once, or reuse a successful decode.
                    validation.withLock {
                        if (!parsed && !validated) open().use { validateAsset(it) }
                        validated = true
                    }
                }, epoch = epoch)
            }
            is SvgaSource.Remote -> return remote(r, source, progress, rejections, epoch)
        }
    }

    private suspend fun remote(r: SvgaRequest, source: SvgaSource.Remote, progress: (SvgaDownloadProgress) -> Unit,
        rejections: SourceRejections, epoch: Long): Source {
        val revision = sourceRevision.incrementAndGet()
        val key = sourceIdentity(r)
        val entry = File(disk, key)
        // Snapshot the entire cache transaction under the same lock used by eviction/publication.
        var cached: File? = null
        val meta = diskMutex.withLock {
            val data = File(entry, "body"); val metaFile = File(entry, "metadata")
            if (r.readsDisk && data.isFile && metaFile.isFile) {
                val metadata = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                if (metadata != null) {
                    val snapshot = createTemporarySvgaFile("cached", staging)
                    try { data.inputStream().use { copy(it, snapshot) }; cached = snapshot }
                    catch (e: Throwable) { snapshot.delete(); throw e }
                }
                metadata
            } else null
        }
        val now = System.currentTimeMillis()
        try {
            if (meta != null && !r.refresh && (r.cacheOnly || meta.optLong("expires") > now)) {
                diskHits.incrementAndGet()
                diskMutex.withLock { entry.setLastModified(now) }
                return diskSource(r, entry, cached!!, meta, rejections, cached = true, epoch = epoch, revision = revision).also { cached = null }
            }
            check(!r.cacheOnly) { "SVGA cache miss" }
            val builder = Request.Builder().url(source.url)
            r.headers.forEach { (k, v) -> builder.header(k, v) }
            if (meta != null && !r.refresh) {
                meta.optString("etag").takeIf { it.isNotEmpty() }?.let { builder.header("If-None-Match", it) }
                meta.optString("modified").takeIf { it.isNotEmpty() }?.let { builder.header("If-Modified-Since", it) }
            }
            val downloaded = partials.fetch(key, builder.build(), r, progress)
            val res = downloaded.response
            val temp = downloaded.file
            try {
                if (res.code == 304 && meta != null) cached!!.inputStream().use { copy(it, temp) }
                else check(res.code != 304) { "HTTP 304 without cached resource" }
                val digest = digest(temp)
                val controlText = res.header("Cache-Control") ?: if (res.code == 304) meta?.optString("control").orEmpty() else ""
                val control = CacheControl.parse(Headers.Builder().add("Cache-Control", controlText).build())
                fun httpDate(value: String?): Long? = value?.let { runCatching {
                    java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(value)?.time
                }.getOrNull() }
                val age = maxOf(res.header("Age")?.toLongOrNull() ?: 0,
                    ((now - (httpDate(res.header("Date")) ?: now)) / 1000).coerceAtLeast(0))
                val expiresHeader = httpDate(res.header("Expires")) ?: now
                val noStore = control.noStore || res.headers.values("Vary").any { it.split(',').any { value -> value.trim() == "*" } }
                val expiry = when {
                    control.noCache -> now
                    control.maxAgeSeconds >= 0 -> now + (control.maxAgeSeconds.toLong() - age).coerceAtLeast(0) * 1000
                    else -> expiresHeader
                }
                val metadata = JSONObject().put("digest", digest).put("expires", expiry).put("control", controlText)
                    .put("etag", res.header("ETag") ?: meta?.optString("etag") ?: "")
                    .put("modified", res.header("Last-Modified") ?: meta?.optString("modified") ?: "")
                    .put("validated", res.code == 304 && meta?.optBoolean("validated", true) == true)
                if (noStore) {
                    if (r.writesDisk) diskMutex.withLock {
                        val current = runCatching { JSONObject(File(entry, "metadata").readText()) }.getOrNull()
                        if (epoch == zipCache.epochLocked && !newerPublication(current, revision)) {
                            entry.deleteRecursively(); zipCache.invalidateSourceLocked(key)
                        }
                    }
                    memory.invalidate(key + ":")
                }
                return if (noStore) Source(temp::inputStream, digest, false, { temp.delete() }, expiry, epoch = epoch)
                else diskSource(r, entry, temp, metadata, rejections, cached = false, epoch = epoch, revision = revision)
            } catch (e: Throwable) { temp.delete(); throw e }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (r.allowStaleOnError && cached != null && meta != null) {
                return diskSource(r, entry, cached!!, meta, rejections, cached = true, expiresAt = 0, epoch = epoch,
                    revision = revision).also { cached = null }
            }
            throw e
        } finally { cached?.delete() }
    }

    private fun diskSource(r: SvgaRequest, entry: File, file: File, metadata: JSONObject,
        rejections: SourceRejections, cached: Boolean,
        expiresAt: Long = metadata.optLong("expires"), epoch: Long, revision: Long): Source {
        val digest = metadata.getString("digest")
        // Legacy entries were only published after decoding, so missing flags mean validated.
        var validated = metadata.optBoolean("validated", true)
        var published = cached
        var publishedValidated = validated
        fun currentMetadata() = runCatching { JSONObject(File(entry, "metadata").readText()) }.getOrNull()
        return Source(file::inputStream, digest, true, { file.delete() }, expiresAt, publish = { parsed ->
            diskMutex.withLock {
                if (epoch != zipCache.epochLocked) return@withLock
                val current = currentMetadata()
                if (current?.optString("digest") == digest && current.optBoolean("validated", true)) validated = true
                if (parsed) validated = true
                if (validated) rejections.digests.remove(digest)
                if (r.writesDisk && digest !in rejections.digests && (!published || (validated && !publishedValidated))) {
                    // A cached snapshot must not overwrite a newer representation or resurrect an eviction.
                    if (published && current?.optString("digest") != digest) return@withLock
                    if (!published && newerPublication(current, revision)) return@withLock
                    val next = File(disk, "${entry.name}-${java.util.UUID.randomUUID()}.tmp")
                    try {
                        ensureSvgaDirectory(disk)
                        ensureSvgaDirectory(next)
                        file.copyTo(File(next, "body"))
                        // A validation upgrade must preserve a newer same-digest HTTP freshness transaction.
                        val nextMetadata = JSONObject((if (published && current != null) current else metadata).toString())
                            .put("validated", validated)
                        if (!published) nextMetadata.put("session", sourceSession).put("revision", revision)
                        File(next, "metadata").writeText(nextMetadata.toString())
                        entry.deleteRecursively()
                        if (!next.renameTo(entry)) throw IOException("Cannot publish SVGA source")
                        zipCache.invalidateSourceLocked(entry.name, keepVersion = digest)
                        trimDisk()
                        published = true; publishedValidated = validated
                    } catch (e: IOException) {
                        android.util.Log.w("SvgaEngine", "Disk cache write failed; using downloaded source", e)
                    } finally { next.deleteRecursively() }
                }
            }
        }, reject = {
            diskMutex.withLock {
                if (epoch != zipCache.epochLocked) return@withLock
                if (!validated) {
                    rejections.digests.add(digest)
                    val current = currentMetadata()
                    if (current?.optString("digest") == digest && !current.optBoolean("validated", true)) {
                        entry.deleteRecursively()
                        zipCache.invalidateSourceLocked(entry.name)
                        published = false
                    }
                }
            }
        }, epoch = epoch, cacheMemory = { store ->
            diskMutex.withLock { if (!newerPublication(currentMetadata(), revision)) store() }
        })
    }

    suspend fun clearDisk() = withContext(Dispatchers.IO) {
        partials.clear()
        diskMutex.withLock { zipCache.clearLocked() }
    }

    private fun trimDisk() {
        zipCache.trimLocked()
    }
    private suspend fun copy(input: java.io.InputStream, file: File, totalBytes: Long? = null,
                             progress: ((SvgaDownloadProgress) -> Unit)? = null) {
        var size = 0L
        var lastProgressNanos = System.nanoTime()
        progress?.invoke(SvgaDownloadProgress(0, totalBytes))
        file.outputStream().use { out ->
            val buffer = ByteArray(8192)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                size += n; require(size <= SvgaResource.MAX_INPUT_BYTES) { "Input too large" }; out.write(buffer, 0, n)
                val now = System.nanoTime()
                if (now - lastProgressNanos >= 100_000_000L) {
                    progress?.invoke(SvgaDownloadProgress(size, totalBytes))
                    lastProgressNanos = now
                }
            }
        }
        currentCoroutineContext().ensureActive()
        progress?.invoke(SvgaDownloadProgress(size, totalBytes, completed = true))
    }
    private suspend fun digest(file: File): String = file.inputStream().use { digest(it) }
    private fun openAsset(path: String): InputStream {
        val input = context.assets.open(path)
        try {
            // AssetInputStream exposes the packaged entry's length without reading its body.
            require(input.available().toLong() <= SvgaResource.MAX_INPUT_BYTES) { "Input too large" }
            return input
        } catch (e: Throwable) { input.close(); throw e }
    }
    private suspend fun validateAsset(input: InputStream) {
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer); if (n < 0) break
            size += n; require(size <= SvgaResource.MAX_INPUT_BYTES) { "Input too large" }
        }
    }
    private suspend fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer); if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private val diskMutex = ZipDirectoryCache.mutex
        private val sourceSession = java.util.UUID.randomUUID().toString()
        private val sourceRevision = AtomicLong()
        private fun newerPublication(metadata: JSONObject?, revision: Long) =
            metadata?.optString("session") == sourceSession && metadata.optLong("revision") > revision
        @Volatile private var shared: SvgaEngine? = null
        fun get(context: Context): SvgaEngine = shared?.takeUnless { it.isClosed } ?: synchronized(this) {
            shared?.takeUnless { it.isClosed } ?: SvgaEngine(context).also { shared = it }
        }
    }
}
