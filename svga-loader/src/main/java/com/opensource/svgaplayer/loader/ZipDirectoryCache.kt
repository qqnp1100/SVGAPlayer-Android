package com.opensource.svgaplayer.loader

import com.opensource.svgaplayer.SvgaResource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/** All *Locked operations share [mutex] with source publication and eviction. */
internal class ZipDirectoryCache(root: File, private val budget: Long) {
    data class Identity(val sourceIdentity: String, val sourceVersion: String, val sourceKind: String) {
        val key = sha256(listOf("zip-v1", sourceIdentity, sourceVersion).joinToString("") { "${it.length}:$it" })
    }
    data class Entry(val identity: Identity, val directory: File, val generation: String, val bytes: Long) {
        val content get() = File(directory, "content")
    }
    class Lease internal constructor(val entry: Entry) {
        internal var released = false
    }
    data class Candidate(val directory: File, val generation: String) {
        val content get() = File(directory, "content")
    }
    private data class Token(val key: String, val generation: String)
    private data class Readers(val entry: Entry, var count: Int = 0)
    private class State {
        @Volatile var epoch = 0L
        val readers = HashMap<Token, Readers>()
        val invalid = HashSet<Token>()
        val pending = HashSet<Token>()
        val staging = HashSet<String>()
    }

    private val root = root.canonicalFile
    private val sources = File(this.root, "sources")
    private val directory = File(this.root, "unzipped")
    private val staging = File(this.root, "staging")
    private val state = synchronized(states) {
        states.entries.removeAll { it.value.get() == null }
        states[this.root.path]?.get() ?: State().also { states[this.root.path] = WeakReference(it) }
    }
    val epochLocked get() = state.epoch
    // Subscription setup must not suspend before registering with SharedFlights.
    fun epoch(): Long = state.epoch
    fun buildLock(identity: Identity): Mutex = buildLocks[(31 * root.path.hashCode() + identity.key.hashCode()) and 63]
    fun isActiveStagingLocked(file: File) = file.canonicalPath in state.staging

    suspend fun createCandidate(): Candidate = mutex.withLock {
        val generation = UUID.randomUUID().toString()
        val result = Candidate(File(staging, "zip-$generation"), generation)
        ensureSvgaDirectory(result.content)
        state.staging.add(result.directory.absolutePath)
        result
    }

    suspend fun discard(candidate: Candidate) = withContext(NonCancellable) {
        mutex.withLock {
            state.staging.remove(candidate.directory.absolutePath)
            candidate.directory.deleteRecursively()
        }
    }

    suspend fun acquire(identity: Identity): Lease? = mutex.withLock {
        val entry = readEntry(File(directory, identity.key), verifyFiles = true) ?: return@withLock null
        if (entry.identity != identity || token(entry) in state.invalid) return@withLock null
        val readers = state.readers.getOrPut(token(entry)) { Readers(entry) }
        readers.count++
        touchLocked(entry)
        Lease(entry)
    }

    suspend fun release(lease: Lease) = withContext(NonCancellable) {
        mutex.withLock {
            if (!lease.released) {
                lease.released = true
                val key = token(lease.entry)
                val readers = checkNotNull(state.readers[key])
                if (--readers.count == 0) {
                    state.readers.remove(key)
                    if (state.pending.remove(key)) deleteEntryLocked(lease.entry)
                }
                trimLocked()
            }
        }
    }

    fun currentLocked(identity: Identity): Entry? = readEntry(File(directory, identity.key), verifyFiles = false)
        ?.takeIf { it.identity == identity }

    fun invalidateLocked(entry: Entry, delete: Boolean = true) {
        val key = token(entry)
        val current = currentLocked(entry.identity)
        if (state.readers[key] == null && current != null && current.generation != entry.generation) return
        state.invalid.add(key)
        if (delete) {
            if ((state.readers[key]?.count ?: 0) > 0) state.pending.add(key)
            else deleteEntryLocked(entry)
        }
    }

    fun invalidateSourceLocked(identity: String, keepVersion: String? = null) {
        directory.listFiles().orEmpty().forEach { file ->
            val entry = readEntry(file, verifyFiles = false) ?: return@forEach
            if (entry.identity.sourceIdentity == identity && entry.identity.sourceVersion != keepVersion) invalidateLocked(entry)
        }
    }

    /** Returns the generation actually retained, including an already published equivalent entry. */
    suspend fun publish(candidate: Candidate, identity: Identity, epoch: Long): Entry? {
        try {
            val files = JSONArray()
            var expanded = 0L
            val prefix = candidate.content.canonicalPath + File.separator
            candidate.content.walkTopDown().forEach { file ->
                currentCoroutineContext().ensureActive()
                require(file == candidate.content || file.canonicalPath.startsWith(prefix)) { "Unsafe ZIP path" }
                if (file.isFile) {
                    expanded += file.length()
                    require(files.length() < 4096 && expanded <= SvgaResource.MAX_EXPANDED_BYTES)
                    files.put(JSONObject().put("path", file.relativeTo(candidate.content).invariantSeparatorsPath)
                        .put("length", file.length()))
                }
            }
            val metadata = JSONObject().put("formatVersion", 1).put("sourceIdentity", identity.sourceIdentity)
                .put("sourceVersion", identity.sourceVersion).put("sourceKind", identity.sourceKind)
                .put("generation", candidate.generation).put("fileCount", files.length())
                .put("expandedBytes", expanded).put("files", files).toString()
            if (metadata.toByteArray().size > MAX_METADATA_BYTES) return null
            File(candidate.directory, "metadata.json").writeText(metadata)
            File(candidate.directory, ".complete").writeText("1")
            return mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (epoch != state.epoch || budget == 0L || !hasSourceLocked(identity)) return@withLock null
                val destination = File(directory, identity.key)
                if (destination.exists() && !destination.isDirectory) return@withLock null
                val existing = readEntry(destination, verifyFiles = true)
                if (existing != null && existing.identity == identity && token(existing) !in state.invalid) {
                    touchLocked(existing)
                    return@withLock existing
                }
                if (isPinnedLocked(identity.key)) return@withLock null
                val bytes = size(candidate.directory)
                val sourceBytes = if (identity.sourceKind == "remote") sourceSize(File(sources, identity.sourceIdentity)) else 0L
                if (bytes > budget - sourceBytes) return@withLock null
                ensureSvgaDirectory(directory)
                removeDirectoryLocked(destination)
                if (!trimLocked(bytes, group(identity))) return@withLock null
                if (!candidate.directory.renameTo(destination)) throw IOException("Cannot publish expanded SVGA ZIP")
                state.invalid.removeAll { it.key == identity.key }
                Entry(identity, destination, candidate.generation, bytes).also { touchLocked(it) }
            }
        } catch (e: IOException) {
            android.util.Log.w("SvgaEngine", "ZIP cache write failed; using decoded resource", e)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("SvgaEngine", "ZIP cache contents changed before publication", e)
        }
        return null
    }

    fun pruneLocked() {
        directory.listFiles().orEmpty().filter { it.isDirectory }.forEach { file ->
            val entry = readEntry(file, verifyFiles = true)
            if (entry == null || !hasSourceLocked(entry.identity)) removeDirectoryLocked(file)
        }
        trimLocked()
    }

    fun clearLocked() {
        state.epoch++
        directory.listFiles().orEmpty().forEach(::removeDirectoryLocked)
        sources.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /** Source and its expanded versions form one eviction unit. Pinned units cannot be evicted. */
    fun trimLocked(additionalBytes: Long = 0, protectedGroup: String? = null): Boolean {
        data class Group(val name: String, val files: MutableList<File> = ArrayList(), var bytes: Long = 0,
            var accessed: Long = 0, var pinned: Boolean = false)
        val groups = LinkedHashMap<String, Group>()
        sources.listFiles().orEmpty().filter { it.isDirectory && !it.name.endsWith(".tmp") }.forEach { file ->
            groups.getOrPut("source/${file.name}") { Group("source/${file.name}") }.apply {
                files.add(file); bytes += sourceSize(file); accessed = maxOf(accessed, file.lastModified())
            }
        }
        directory.listFiles().orEmpty().filter { it.isDirectory }.forEach { file ->
            val entry = readEntry(file, verifyFiles = false)
            val name = entry?.let { group(it.identity) } ?: "zip/${file.name}"
            groups.getOrPut(name) { Group(name) }.apply {
                files.add(file); bytes += size(file); accessed = maxOf(accessed, file.lastModified())
                pinned = pinned || isPinnedLocked(file.name)
            }
        }
        var total = groups.values.sumOf { it.bytes }
        for (unit in groups.values.sortedBy { it.accessed }) {
            if (total <= budget - additionalBytes) break
            if (unit.pinned || unit.name == protectedGroup) continue
            unit.files.forEach { file ->
                if (file.parentFile == directory) removeDirectoryLocked(file) else file.deleteRecursively()
            }
            total -= unit.bytes
        }
        return total <= budget - additionalBytes
    }

    private fun hasSourceLocked(identity: Identity): Boolean {
        if (identity.sourceKind != "remote") return true
        val entry = File(sources, identity.sourceIdentity)
        return File(entry, "body").isFile && runCatching {
            JSONObject(File(entry, "metadata").readText()).getString("digest") == identity.sourceVersion
        }.getOrDefault(false)
    }

    private fun touchLocked(entry: Entry) {
        val now = System.currentTimeMillis()
        entry.directory.setLastModified(now)
        if (entry.identity.sourceKind == "remote") File(sources, entry.identity.sourceIdentity).setLastModified(now)
    }

    private fun isPinnedLocked(key: String) = state.readers.any { it.key.key == key && it.value.count > 0 }
    private fun token(entry: Entry) = Token(entry.identity.key, entry.generation)
    private fun group(identity: Identity) = if (identity.sourceKind == "remote") "source/${identity.sourceIdentity}" else "zip/${identity.key}"
    private fun sourceSize(file: File) = File(file, "body").length() + File(file, "metadata").length()

    private fun deleteEntryLocked(entry: Entry) {
        val current = readEntry(entry.directory, verifyFiles = false)
        if (!isPinnedLocked(entry.identity.key) && (current == null || current.generation == entry.generation)) {
            if (entry.directory.deleteRecursively()) state.invalid.remove(token(entry))
        }
    }

    private fun removeDirectoryLocked(file: File) {
        val readers = state.readers.values.filter { it.entry.directory == file }
        if (readers.isEmpty()) {
            file.deleteRecursively()
            state.invalid.removeAll { it.key == file.name }
        } else readers.forEach { invalidateLocked(it.entry) }
    }

    private fun readEntry(file: File, verifyFiles: Boolean): Entry? = runCatching {
        val metadataFile = File(file, "metadata.json")
        require(file.isDirectory && File(file, ".complete").isFile && metadataFile.length() in 1..MAX_METADATA_BYTES.toLong())
        val metadata = JSONObject(metadataFile.readText())
        require(metadata.getInt("formatVersion") == 1)
        val identity = Identity(metadata.getString("sourceIdentity"), metadata.getString("sourceVersion"), metadata.getString("sourceKind"))
        require(file.name == identity.key && identity.sourceKind in listOf("remote", "file", "asset"))
        require(identity.sourceIdentity.matches(Regex("[0-9a-f]{64}")))
        val generation = metadata.getString("generation")
        require(generation.isNotEmpty())
        if (verifyFiles) {
            val content = File(file, "content")
            val prefix = content.canonicalPath + File.separator
            val files = metadata.getJSONArray("files")
            require(files.length() == metadata.getInt("fileCount") && files.length() in 1..4096)
            var total = 0L
            val paths = HashSet<String>()
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val path = item.getString("path")
                require(paths.add(path) && !File(path).isAbsolute)
                val child = File(content, path).canonicalFile
                val length = item.getLong("length")
                require(child.path.startsWith(prefix) && child.isFile && length >= 0 && child.length() == length)
                total += length
                require(total <= SvgaResource.MAX_EXPANDED_BYTES)
            }
            require(total == metadata.getLong("expandedBytes"))
            require(("movie.binary" in paths && File(content, "movie.binary").isFile) ||
                ("movie.spec" in paths && File(content, "movie.spec").isFile))
            var actualFiles = 0
            content.walkTopDown().forEach { child ->
                require(child == content || child.canonicalPath.startsWith(prefix))
                if (child.isFile) require(child.relativeTo(content).invariantSeparatorsPath in paths && ++actualFiles <= files.length())
            }
            require(actualFiles == files.length())
        }
        Entry(identity, file, generation, size(file))
    }.getOrNull()

    private fun size(file: File): Long = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        val mutex = Mutex()
        private val buildLocks = Array(64) { Mutex() }
        private val states = HashMap<String, WeakReference<State>>()
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
