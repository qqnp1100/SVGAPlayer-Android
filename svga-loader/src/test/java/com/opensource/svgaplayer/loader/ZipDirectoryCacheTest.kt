package com.opensource.svgaplayer.loader

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ZipDirectoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = ZipDirectoryCache.Identity("a".repeat(64), "version", "file")
    private suspend fun publish(cache: ZipDirectoryCache): ZipDirectoryCache.Entry {
        val candidate = cache.createCandidate()
        try {
            File(candidate.content, "movie.spec").writeText("movie")
            return checkNotNull(cache.publish(candidate, identity, cache.epoch()))
        } finally { cache.discard(candidate) }
    }

    @Test fun onlyCompleteManifestsAreReadableAndPayloadCannotForgeMarkers() = runBlocking {
        val cache = ZipDirectoryCache(temporary.root, 8192)
        val entry = publish(cache)
        val lease = checkNotNull(cache.acquire(identity)); cache.release(lease)
        File(entry.directory, ".complete").delete()
        File(entry.content, ".complete").writeText("1")
        assertNull(cache.acquire(identity))
    }

    @Test fun clearHidesPinnedDirectoryUntilLastReaderReleasesIt() = runBlocking {
        val first = ZipDirectoryCache(temporary.root, 8192)
        val second = ZipDirectoryCache(temporary.root, 8192)
        val entry = publish(first)
        val lease = checkNotNull(second.acquire(identity))
        ZipDirectoryCache.mutex.withLock { first.clearLocked() }
        assertNull(second.acquire(identity))
        assertTrue(entry.content.isDirectory)
        second.release(lease)
        assertFalse(entry.directory.exists())
    }

    @Test fun clearRejectsCandidatesFromAnEarlierEpochAcrossInstances() = runBlocking {
        val first = ZipDirectoryCache(temporary.root, 8192)
        val second = ZipDirectoryCache(temporary.root, 8192)
        val epoch = first.epoch()
        val candidate = first.createCandidate()
        try {
            File(candidate.content, "movie.spec").writeText("movie")
            ZipDirectoryCache.mutex.withLock { second.clearLocked() }
            assertNull(first.publish(candidate, identity, epoch))
            assertNull(first.acquire(identity))
        } finally { first.discard(candidate) }
    }

    @Test fun delayedInvalidationCannotRemoveAReplacementGeneration() = runBlocking {
        val cache = ZipDirectoryCache(temporary.root, 8192)
        val old = publish(cache)
        ZipDirectoryCache.mutex.withLock { cache.invalidateLocked(old) }
        val replacement = publish(cache)
        assertNotEquals(old.generation, replacement.generation)
        ZipDirectoryCache.mutex.withLock { cache.invalidateLocked(old) }
        val lease = checkNotNull(cache.acquire(identity))
        assertEquals(replacement.generation, lease.entry.generation)
        cache.release(lease)
    }

    @Test fun evictionCannotRemovePinnedFilesOrAdmitAnOverBudgetCandidate() = runBlocking {
        val cache = ZipDirectoryCache(temporary.root, 2000)
        val entry = publish(cache)
        val lease = checkNotNull(cache.acquire(identity))
        val small = ZipDirectoryCache(temporary.root, 0)
        ZipDirectoryCache.mutex.withLock { assertFalse(small.trimLocked()) }
        assertTrue(entry.content.isDirectory)
        small.release(lease)
        assertFalse(entry.directory.exists())
        val candidate = cache.createCandidate()
        try {
            File(candidate.content, "movie.spec").writeBytes(ByteArray(3000))
            assertNull(cache.publish(candidate, identity, cache.epoch()))
        } finally { cache.discard(candidate) }
    }

    @Test fun readonlyInvalidationPreservesFilesButStopsReuse() = runBlocking {
        val cache = ZipDirectoryCache(temporary.root, 8192)
        val entry = publish(cache)
        ZipDirectoryCache.mutex.withLock { cache.invalidateLocked(entry, delete = false) }
        assertNull(cache.acquire(identity))
        assertTrue(entry.directory.isDirectory)
        val replacement = publish(cache)
        assertNotEquals(entry.generation, replacement.generation)
    }
}
