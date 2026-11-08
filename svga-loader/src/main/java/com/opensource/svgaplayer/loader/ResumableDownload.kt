package com.opensource.svgaplayer.loader

import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Partial bodies are private checkpoints, never readable as completed cache entries. */
internal class ResumableDownload(
    private val directory: File,
    private val staging: File,
    private val client: OkHttpClient,
    private val budget: Long,
    private val onRequest: () -> Unit,
) {
    data class Result(val file: File, val response: Response)
    private data class Checkpoint(val etag: String, val total: Long?, val url: String, val headers: Headers)

    suspend fun fetch(key: String, request: Request, options: SvgaRequest,
                      progress: (SvgaDownloadProgress) -> Unit): Result {
        try { return lock(key).withLock { fetchLocked(key, request, options, progress) } }
        finally { prune() }
    }

    private suspend fun fetchLocked(key: String, request: Request, options: SvgaRequest,
                                    progress: (SvgaDownloadProgress) -> Unit): Result {
        val entry = File(directory, key)
        val bodyFile = File(entry, "body")
        val metadataFile = File(entry, "metadata")
        val automatic = request.header("Range") == null && request.header("If-Range") == null &&
            (request.header("Accept-Encoding") == null || request.header("Accept-Encoding").equals("identity", true))
        val maySave = options.resumeDownloads && options.writesDisk && budget > 0 && automatic
        var checkpoint = if (maySave && options.readsDisk && !options.refresh) read(entry) else null
        if (options.refresh && options.writesDisk) entry.deleteRecursively()
        var retryFull = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val offset = if (checkpoint != null) bodyFile.length() else 0L
            val builder = request.newBuilder()
            // Byte offsets must describe the stored representation, not transparent gzip output.
            if (maySave) builder.header("Accept-Encoding", "identity")
            if (checkpoint != null || retryFull) {
                builder.removeHeader("If-None-Match").removeHeader("If-Modified-Since")
                    .removeHeader("Range").removeHeader("If-Range")
            }
            checkpoint?.let { builder.header("Range", "bytes=$offset-").header("If-Range", it.etag) }
            val call = client.newCall(builder.build())
            onRequest()
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            val cancel = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                response.use { raw ->
                    val range = parseRange(raw.header("Content-Range"))
                    val append = checkpoint != null && raw.code == 206 && range != null &&
                        range.first == offset && range.second == range.third - 1 &&
                        (checkpoint!!.total == null || checkpoint!!.total == range.third) &&
                        raw.header("ETag") == checkpoint!!.etag && raw.request.url.toString() == checkpoint!!.url &&
                        identityEncoding(raw) &&
                        (raw.body!!.contentLength() < 0 || raw.body!!.contentLength() == range.third - offset)
                    if (checkpoint != null && (raw.code == 416 || raw.code == 304 || (raw.code == 206 && !append))) {
                        // Never guess how malformed or changed ranges fit. One unconditional retry only.
                        entry.deleteRecursively(); checkpoint = null; retryFull = true
                    } else {
                        if (raw.code != 200 && raw.code != 304 && !append) throw IOException("HTTP ${raw.code}: expected a complete SVGA body")
                        val headers = raw.headers.newBuilder()
                        if (append) checkpoint!!.headers.forEach { (name, value) ->
                            if (raw.header(name) == null) headers.add(name, value)
                        }
                        val res = raw.newBuilder().headers(headers.build()).build()
                        val noStore = res.cacheControl.noStore || res.headers.values("Vary").any {
                            it.split(',').any { value -> value.trim() == "*" }
                        }
                        val total = if (append) range!!.third else raw.body?.contentLength()?.takeIf { it >= 0 }
                        val etag = strongEtag(res.header("ETag"))
                        val keep = maySave && !noStore && identityEncoding(raw) && etag != null &&
                            (total == null || total <= minOf(budget, SvgaResource.MAX_INPUT_BYTES)) && raw.code != 304
                        val temp = File.createTempFile("download", ".svga", staging)
                        var target = temp
                        try {
                            if (append && !keep) bodyFile.copyTo(temp, overwrite = true)
                            if (!keep || !append) entry.deleteRecursively()
                            if (keep) {
                                check(entry.mkdirs() || entry.isDirectory)
                                target = bodyFile
                                if (!append) FileOutputStream(target).close()
                                val savedHeaders = JSONObject()
                                carriedHeaders.forEach { name -> res.header(name)?.let { savedHeaders.put(name, it) } }
                                val data = JSONObject().put("etag", etag).put("total", total ?: -1)
                                    .put("url", raw.request.url.toString()).put("headers", savedHeaders)
                                val next = File(entry, "metadata.tmp")
                                next.writeText(data.toString())
                                // Old metadata describes the same representation when appending.
                                check(moveReplacing(next, metadataFile)) { "Unable to save download checkpoint" }
                                entry.setLastModified(System.currentTimeMillis())
                            }
                            if (raw.code != 304) copyBody(raw.body ?: throw IOException("Missing response body"), target,
                                if (append) offset else 0L, total, progress)
                            currentCoroutineContext().ensureActive()
                            if (keep) {
                                check(moveReplacing(target, temp)) { "Unable to finish download checkpoint" }
                                entry.deleteRecursively()
                            }
                            return Result(temp, res)
                        } catch (e: Throwable) {
                            temp.delete()
                            // Keep only a valid prefix after transport interruption or cancellation.
                            if (!keep || (e !is IOException && e !is CancellationException) ||
                                bodyFile.length() <= 0 || bodyFile.length() > minOf(budget, SvgaResource.MAX_INPUT_BYTES) ||
                                (total != null && bodyFile.length() >= total)) entry.deleteRecursively()
                            throw e
                        }
                    }
                }
            } finally { cancel.cancel() }
        }
    }

    private suspend fun copyBody(body: ResponseBody, file: File, offset: Long, total: Long?,
                                 progress: (SvgaDownloadProgress) -> Unit) {
        var size = offset
        var lastProgress = System.nanoTime()
        progress(SvgaDownloadProgress(size, total))
        body.byteStream().use { input -> FileOutputStream(file, offset > 0).use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= SvgaResource.MAX_INPUT_BYTES && (total == null || size <= total)) { "Invalid SVGA body length" }
                output.write(buffer, 0, count)
                val now = System.nanoTime()
                if (now - lastProgress >= 100_000_000L) {
                    progress(SvgaDownloadProgress(size, total)); lastProgress = now
                }
            }
        } }
        if (total != null && size != total) throw IOException("Incomplete SVGA body: $size / $total")
        currentCoroutineContext().ensureActive()
        progress(SvgaDownloadProgress(size, total, completed = true))
    }

    private fun read(entry: File): Checkpoint? = runCatching {
        check(System.currentTimeMillis() - entry.lastModified() <= MAX_AGE)
        val data = JSONObject(File(entry, "metadata").readText())
        val bytes = File(entry, "body").length()
        val total = data.getLong("total").takeIf { it >= 0 }
        check(bytes > 0 && bytes <= minOf(budget, SvgaResource.MAX_INPUT_BYTES) && (total == null || bytes < total))
        val headers = Headers.Builder()
        val stored = data.getJSONObject("headers")
        carriedHeaders.forEach { name -> stored.optString(name).takeIf { it.isNotEmpty() }?.let { headers.add(name, it) } }
        Checkpoint(strongEtag(data.getString("etag")) ?: error("Missing strong ETag"), total, data.getString("url"), headers.build())
    }.getOrNull()

    /** Try-lock skips active transfers; their quota is enforced when they finish or fail. */
    fun prune() {
        val entries = directory.listFiles().orEmpty().sortedBy { it.lastModified() }
        var bytes = entries.sumOf { File(it, "body").length() }
        val expiry = System.currentTimeMillis() - MAX_AGE
        for (entry in entries) {
            val mutex = lock(entry.name)
            if (!mutex.tryLock()) continue
            try {
                if (entry.lastModified() < expiry || bytes > budget || read(entry) == null) {
                    bytes -= File(entry, "body").length(); entry.deleteRecursively()
                }
            } finally { mutex.unlock() }
        }
    }

    suspend fun clear() {
        // Also wait for an active transfer that has not written its checkpoint yet.
        for (index in locks.indices) locks[index].withLock {
            directory.listFiles().orEmpty().filter { stripe(it.name) == index }.forEach { it.deleteRecursively() }
        }
    }

    companion object {
        private fun moveReplacing(source: File, target: File): Boolean {
            if (source.renameTo(target)) return true
            // Windows does not replace existing files via renameTo. Both targets are private:
            // the old checkpoint describes the same representation; the staging file is empty.
            return target.isFile && target.delete() && source.renameTo(target)
        }
        private val locks = Array(64) { Mutex() }
        private val MAX_AGE = TimeUnit.HOURS.toMillis(24)
        private val carriedHeaders = listOf("ETag", "Last-Modified", "Cache-Control", "Expires", "Vary")
        private fun stripe(key: String) = (key.hashCode() and Int.MAX_VALUE) % locks.size
        private fun lock(key: String) = locks[stripe(key)]
        private fun identityEncoding(response: Response) = response.header("Content-Encoding")?.equals("identity", true) != false
        private fun strongEtag(value: String?): String? = value?.takeIf {
            it.length >= 2 && it.startsWith('"') && it.endsWith('"') && it.substring(1, it.length - 1).none { c -> c == '"' || c.code < 0x21 }
        }
        private fun parseRange(value: String?): Triple<Long, Long, Long>? {
            val parts = value?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) }?.groupValues ?: return null
            val start = parts[1].toLongOrNull() ?: return null
            val end = parts[2].toLongOrNull() ?: return null
            val total = parts[3].toLongOrNull() ?: return null
            return if (start <= end && end < total && total <= SvgaResource.MAX_INPUT_BYTES) Triple(start, end, total) else null
        }
    }
}
