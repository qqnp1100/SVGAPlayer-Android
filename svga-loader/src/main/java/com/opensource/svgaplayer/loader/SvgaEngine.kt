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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SvgaEngine(
    context: Context,
    val memoryBytes: Long = 32L * 1024 * 1024,
    val diskBytes: Long = 128L * 1024 * 1024,
    client: OkHttpClient = OkHttpClient(),
    downloadConcurrency: Int = 4,
    decodeConcurrency: Int = 2,
    val maxDecodedBytes: Long = 128L * 1024 * 1024,
    val weakMemoryCacheEnabled: Boolean = true,
) : AutoCloseable {
    init { require(memoryBytes >= 0 && diskBytes >= 0 && maxDecodedBytes > 0) }
    private val context = context.applicationContext
    private val root = File(context.cacheDir, "svga-engine").apply { mkdirs() }
    private val staging = File(root, "staging").apply { mkdirs() }
    private val disk = File(root, "sources").apply { mkdirs() }
    private val client = client.newBuilder().cache(null).callTimeout(45, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = Semaphore(downloadConcurrency)
    private val decodes = Semaphore(decodeConcurrency)
    private data class Source(val file: File, val digest: String, val reusable: Boolean, val temporary: Boolean, val expiresAt: Long = Long.MAX_VALUE, val publish: suspend () -> Unit = {})
    private val sources = SharedFlights<String, Source>(scope) { if (it.temporary) it.file.delete() }
    private val resources = SharedFlights<String, SvgaResource>(scope)
    private val memory = ResourceMemoryCache<SvgaResource>(memoryBytes) { it.sizeBytes }
    val weakMemoryHits = AtomicLong()
    val networkRequests = AtomicLong()
    val decodeCount = AtomicLong()
    val memoryHits = AtomicLong()
    val diskHits = AtomicLong()
    private val partials = ResumableDownload(File(root, "partials").apply { mkdirs() }, staging, this.client,
        minOf(diskBytes, SvgaResource.MAX_INPUT_BYTES)) { networkRequests.incrementAndGet() }

    init {
        scope.launch { diskMutex.withLock {
            val abandonedBefore = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
            staging.listFiles().orEmpty().filter { it.lastModified() < abandonedBefore }.forEach { it.deleteRecursively() }
            disk.listFiles().orEmpty().filter { it.name.endsWith(".tmp") && it.lastModified() < abandonedBefore }.forEach { it.deleteRecursively() }
            trimDisk()
        }; partials.prune() }
    }

    suspend fun acquire(request: SvgaRequest, onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = request.onDownloadProgress): SvgaResource {
        val r = request.snapshot()
        val decodeOptions = r.resolveDecodeOptions()
        val decodeKey = "${r.width}:${r.height}:${decodeOptions.bitmapConfig.name}:${decodeOptions.skipInvisibleImages}"
        check(scope.isActive) { "Engine closed" }
        val lookupKey = "${sourceIdentity(r)}:$decodeKey"
        val weakEnabled = weakMemoryCacheEnabled && r.usesWeakMemory
        if (r.refresh) memory.invalidate(sourceIdentity(r) + ":")
        if (!r.refresh) memory.find(lookupKey, r.readsMemory, weakEnabled, r.cacheOnly)?.let { hit ->
            if (hit.weak) weakMemoryHits.incrementAndGet() else memoryHits.incrementAndGet()
            memory.put(hit.key, lookupKey, hit.expires, hit.value, r.writesMemory, weakEnabled)
            return hit.value
        }
        return sources.useWithProgress(sourceKey(r), onDownloadProgress, { progress -> downloads.withPermit { source(r, progress) } }) { source ->
            val key = "${sourceIdentity(r)}:${source.digest}:$decodeKey"
            val hit = if (source.reusable && !r.refresh) memory.findKey(key, r.readsMemory, weakEnabled) else null
            if (hit != null) {
                if (hit.weak) weakMemoryHits.incrementAndGet() else memoryHits.incrementAndGet()
                withContext(Dispatchers.IO) { source.publish() }
                memory.put(key, lookupKey, source.expiresAt, hit.value, r.writesMemory, weakEnabled)
                hit.value
            } else {
                resources.use("$key:${sourceKey(r)}", {
                    decodes.withPermit {
                        val dir = File(staging, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
                        try {
                            decodeCount.incrementAndGet()
                            val resource = source.file.inputStream().use { SvgaResource.decode(it, dir, r.width, r.height, maxDecodedBytes, decodeOptions) }
                            source.publish()
                            resource
                        } finally { dir.deleteRecursively() }
                    }
                }, { resource ->
                    // Publish each subscriber's cache policy before releasing its shared flight.
                    if (source.reusable) memory.put(key, lookupKey, source.expiresAt, resource, r.writesMemory, weakEnabled)
                    resource
                })
            }
        }
    }

    suspend fun preload(request: SvgaRequest) { acquire(request) }
    fun clearMemory() = memory.clear()
    override fun close() { scope.cancel(); clearMemory() }

    private fun sourceIdentity(r: SvgaRequest): String {
        val identity = when (val source = r.source) {
            is SvgaSource.LocalFile -> "${source.file.canonicalPath}:${source.file.length()}:${source.file.lastModified()}:${source.version}"
            else -> source.toString()
        }
        val parts = listOf(identity, r.namespace) + r.headers.flatMap { listOf(it.key, it.value) }
        return hash(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
    }
    private fun sourceKey(r: SvgaRequest) = "${sourceIdentity(r)}:${r.readsDisk}:${r.writesDisk}:${r.cacheOnly}:${r.refresh}:${r.allowStaleOnError}:${r.resumeDownloads}"

    private suspend fun source(r: SvgaRequest, progress: (SvgaDownloadProgress) -> Unit): Source {
        when (val source = r.source) {
            is SvgaSource.LocalFile -> return Source(source.file, digest(source.file), true, false)
            is SvgaSource.Asset -> {
                val file = File.createTempFile("asset", ".svga", staging)
                try { context.assets.open(source.path).use { copy(it, file) }; return Source(file, digest(file), true, true) }
                catch (e: Throwable) { file.delete(); throw e }
            }
            is SvgaSource.Remote -> return remote(r, source, progress)
        }
    }

    private suspend fun remote(r: SvgaRequest, source: SvgaSource.Remote, progress: (SvgaDownloadProgress) -> Unit): Source {
        val key = sourceIdentity(r)
        val entry = File(disk, key)
        // Snapshot the entire cache transaction under the same lock used by eviction/publication.
        var cached: File? = null
        val meta = diskMutex.withLock {
            val data = File(entry, "body"); val metaFile = File(entry, "metadata")
            if (r.readsDisk && data.isFile && metaFile.isFile) {
                val metadata = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                if (metadata != null) {
                    val snapshot = File.createTempFile("cached", ".svga", staging)
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
                return Source(cached!!, meta.getString("digest"), true, true, meta.optLong("expires")).also { cached = null }
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
                if (noStore) {
                    if (r.writesDisk) diskMutex.withLock { entry.deleteRecursively() }
                    memory.invalidate(key + ":")
                }
                var published = false
                // Publish only AFTER successful decoding. Invalid downloads never become cache hits.
                return Source(temp, digest, !noStore, true, expiry) {
                    if (r.writesDisk && !noStore) diskMutex.withLock {
                        if (!published) {
                            val next = File(disk, "$key-${java.util.UUID.randomUUID()}.tmp").apply { mkdirs() }
                            try {
                                temp.copyTo(File(next, "body"))
                                File(next, "metadata").writeText(metadata.toString())
                                entry.deleteRecursively(); check(next.renameTo(entry)); trimDisk(); published = true
                            } catch (e: IOException) {
                                android.util.Log.w("SvgaEngine", "Disk cache write failed; using prepared resource", e)
                            } finally { next.deleteRecursively() }
                        }
                    }
                }
            } catch (e: Throwable) { temp.delete(); throw e }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (r.allowStaleOnError && cached != null && meta != null) {
                return Source(cached!!, meta.getString("digest"), true, true, 0).also { cached = null }
            }
            throw e
        } finally { cached?.delete() }
    }

    suspend fun clearDisk() = withContext(Dispatchers.IO) {
        partials.clear()
        diskMutex.withLock { disk.listFiles().orEmpty().forEach { it.deleteRecursively() } }
    }

    private fun trimDisk() {
        val entries = disk.listFiles().orEmpty().filter { !it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }
        var total = entries.sumOf { File(it, "body").length() + File(it, "metadata").length() }
        for (entry in entries) { if (total <= diskBytes) break
            total -= File(entry, "body").length() + File(entry, "metadata").length(); entry.deleteRecursively() }
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
    private suspend fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private val diskMutex = Mutex()
        @Volatile private var shared: SvgaEngine? = null
        fun get(context: Context): SvgaEngine = shared ?: synchronized(this) { shared ?: SvgaEngine(context).also { shared = it } }
    }
}
