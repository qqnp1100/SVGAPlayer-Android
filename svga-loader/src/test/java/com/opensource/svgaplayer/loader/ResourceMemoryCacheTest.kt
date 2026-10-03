package com.opensource.svgaplayer.loader

import java.lang.ref.Reference
import org.junit.Assert.*
import org.junit.Test

class ResourceMemoryCacheTest {
    @Test fun liveResourceSurvivesLruEvictionInWeakIndex() {
        val cache = ResourceMemoryCache<Any>(1) { 1 }
        val first = Any(); val second = Any()
        cache.put("a:hash", "a", Long.MAX_VALUE, first, true, true)
        cache.put("b:hash", "b", Long.MAX_VALUE, second, true, true)
        assertNull(cache.find("a", true, false, false))
        assertSame(first, cache.find("a", false, true, false)!!.value)
        assertSame(second, cache.find("b", true, false, false)!!.value)
    }
    @Test fun weakOnlyAndStrongOnlyAreIndependent() {
        val cache = ResourceMemoryCache<Any>(10) { 1 }
        val value = Any()
        cache.put("weak", "w", Long.MAX_VALUE, value, false, true)
        cache.put("strong", "s", Long.MAX_VALUE, value, true, false)
        assertNull(cache.find("w", true, false, false))
        assertNull(cache.find("s", false, true, false))
        assertSame(value, cache.find("w", false, true, false)!!.value)
        assertSame(value, cache.find("s", true, false, false)!!.value)
    }
    @Test fun expiryClearAndInvalidationApplyToBothLayers() {
        val cache = ResourceMemoryCache<Any>(10) { 1 }
        val value = Any()
        cache.put("a:old", "a", 0, value, true, true)
        assertNull(cache.find("a", true, true, false))
        assertSame(value, cache.find("a", false, true, true)!!.value)
        cache.invalidate("a:")
        assertNull(cache.findKey("a:old", true, true))
        cache.put("b:new", "b", Long.MAX_VALUE, value, true, true)
        cache.clear(); assertNull(cache.find("b", true, true, true))
    }
    @Test fun collectedReferencesRemoveKeysAndCannotRemoveReplacement() {
        val cache = ResourceMemoryCache<Any>(0) { 1 }
        val old = Any(); val replacement = Any()
        cache.put("key", "alias", Long.MAX_VALUE, old, false, true)
        val field = cache.javaClass.getDeclaredField("weak").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val entries = field.get(cache) as Map<String, Reference<Any>>
        val previous = entries.getValue("key")
        cache.put("key", "alias", Long.MAX_VALUE, replacement, false, true)
        previous.clear(); previous.enqueue()
        assertSame(replacement, cache.find("alias", false, true, false)!!.value)
        val current = entries.getValue("key"); current.clear(); current.enqueue()
        assertNull(cache.find("alias", false, true, false)); assertTrue(entries.isEmpty())
    }
    @Test fun newVersionInOneTierInvalidatesOldLookupInOtherTier() {
        val cache = ResourceMemoryCache<Any>(10) { 1 }
        val old = Any(); val fresh = Any()
        cache.put("a:old", "a", Long.MAX_VALUE, old, false, true)
        cache.put("a:new", "a", Long.MAX_VALUE, fresh, true, false)
        assertNull(cache.find("a", false, true, false))
        assertSame(fresh, cache.find("a", true, false, false)!!.value)
    }
    @Test fun requestDefaultsAndOverrides() {
        val request = SvgaRequest(SvgaSource.Asset("test"))
        assertTrue(request.usesWeakMemory); assertTrue(request.readsMemory)
        assertTrue(request.copy(memoryCache = false).usesWeakMemory)
        assertFalse(request.copy(memoryCache = false).writesMemory)
        assertFalse(request.copy(cachePolicy = SvgaCachePolicy.NONE).usesWeakMemory)
        assertTrue(request.copy(cachePolicy = SvgaCachePolicy.NONE, weakMemoryCache = true).usesWeakMemory)
        assertTrue(request.copy(memoryCache = false, memoryWrite = true).writesMemory)
    }
}
