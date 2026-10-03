package com.opensource.svgaplayer.loader

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** All operations are serialized; values in the weak index never keep resources alive. */
internal class ResourceMemoryCache<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    data class Hit<V>(val value: V, val weak: Boolean, val key: String, val expires: Long)
    private data class Strong<V>(val value: V, val bytes: Long)
    private data class Alias(val key: String, val expires: Long)
    private class Entry<V : Any>(val key: String, val lookup: String, val expires: Long, value: V,
                               queue: ReferenceQueue<V>) : WeakReference<V>(value, queue)
    private val strong = LinkedHashMap<String, Strong<V>>(16, .75f, true)
    private val aliases = HashMap<String, Alias>()
    private val weak = HashMap<String, Entry<V>>()
    private val weakAliases = HashMap<String, Entry<V>>()
    private val queue = ReferenceQueue<V>()
    private var bytes = 0L

    private fun drain() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val entry = queue.poll() as Entry<V>? ?: break
            remove(entry)
        }
    }
    private fun remove(entry: Entry<V>) {
        if (weak[entry.key] === entry) weak.remove(entry.key)
        if (weakAliases[entry.lookup] === entry) weakAliases.remove(entry.lookup)
    }
    @Synchronized fun find(lookup: String, readStrong: Boolean, readWeak: Boolean, cacheOnly: Boolean): Hit<V>? {
        drain()
        val now = System.currentTimeMillis()
        if (readStrong) aliases[lookup]?.let { alias ->
            if (cacheOnly || alias.expires > now) strong[alias.key]?.let {
                return Hit(it.value, false, alias.key, alias.expires)
            }
        }
        if (readWeak) weakAliases[lookup]?.let { entry ->
            val value = entry.get()
            if (value == null) remove(entry)
            else if (cacheOnly || entry.expires > now) return Hit(value, true, entry.key, entry.expires)
        }
        return null
    }
    @Synchronized fun findKey(key: String, readStrong: Boolean, readWeak: Boolean): Hit<V>? {
        drain()
        if (readStrong) strong[key]?.let { return Hit(it.value, false, key, 0) }
        if (readWeak) weak[key]?.let { entry ->
            val value = entry.get()
            if (value == null) remove(entry) else return Hit(value, true, key, entry.expires)
        }
        return null
    }
    @Synchronized fun put(key: String, lookup: String, expires: Long, value: V, writeStrong: Boolean, writeWeak: Boolean) {
        drain()
        if (writeStrong || writeWeak) {
            if (aliases[lookup]?.key != key) aliases.remove(lookup)
            weakAliases[lookup]?.let { if (it.key != key) weakAliases.remove(lookup) }
        }
        if (writeWeak) {
            weak.put(key, Entry(key, lookup, expires, value, queue))?.let { old ->
                if (weakAliases[old.lookup] === old) weakAliases.remove(old.lookup)
            }
            weakAliases[lookup] = weak.getValue(key)
        }
        if (writeStrong) {
            val cost = sizeOf(value)
            strong.put(key, Strong(value, cost))?.let { bytes -= it.bytes }
            bytes += cost; aliases[lookup] = Alias(key, expires)
            val iterator = strong.entries.iterator()
            while (bytes > maxBytes && iterator.hasNext()) {
                val removed = iterator.next(); bytes -= removed.value.bytes; iterator.remove()
                aliases.entries.removeAll { it.value.key == removed.key }
            }
        }
    }
    @Synchronized fun invalidate(prefix: String) {
        drain()
        val iterator = strong.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) { bytes -= entry.value.bytes; iterator.remove() }
        }
        aliases.entries.removeAll { it.value.key.startsWith(prefix) }
        weak.values.filter { it.key.startsWith(prefix) }.forEach { remove(it) }
    }
    @Synchronized fun clear() {
        strong.clear(); aliases.clear(); weak.clear(); weakAliases.clear(); bytes = 0; drain()
    }
}
