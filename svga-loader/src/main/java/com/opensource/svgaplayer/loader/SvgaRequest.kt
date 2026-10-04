package com.opensource.svgaplayer.loader

import android.graphics.Bitmap
import com.opensource.svgaplayer.SvgaDecodeOptions
import java.io.File
import java.net.URI

sealed interface SvgaSource {
    data class Remote(val url: String, val version: String? = null) : SvgaSource
    data class LocalFile(val file: File, val version: String? = null) : SvgaSource
    data class Asset(val path: String) : SvgaSource
    companion object {
        fun from(value: Any): SvgaSource = when (value) {
            is SvgaSource -> value
            is File -> LocalFile(value)
            is String -> when {
                value.startsWith("https://") || value.startsWith("http://") -> Remote(value)
                value.startsWith("file:///android_asset/") -> Asset(value.removePrefix("file:///android_asset/"))
                value.startsWith("file://") -> LocalFile(File(URI(value)))
                else -> error("Use SvgaSource.Asset for asset paths, or File for local files")
            }
            else -> error("Unsupported SVGA source: ${value.javaClass.name}")
        }
    }
}

enum class SvgaCachePolicy(val memory: Boolean, val disk: Boolean) {
    NONE(false, false), MEMORY(true, false), DISK(false, true), ALL(true, true)
}

data class SvgaRequest(
    val source: SvgaSource,
    val width: Int = 0,
    val height: Int = 0,
    val cachePolicy: SvgaCachePolicy = SvgaCachePolicy.ALL,
    val headers: Map<String, String> = emptyMap(),
    val namespace: String = "default",
    val cacheOnly: Boolean = false,
    val refresh: Boolean = false,
    val memoryRead: Boolean? = null,
    val memoryWrite: Boolean? = null,
    val diskRead: Boolean? = null,
    val diskWrite: Boolean? = null,
    val allowStaleOnError: Boolean = false,
    /** Called serially in the acquiring coroutine context; slow observers receive conflated updates. */
    val onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = null,
    /** Retain validated HTTP prefixes when disk writes are allowed; read them when disk reads are allowed. */
    val resumeDownloads: Boolean = true,
    /** Strong LRU override. Null inherits cachePolicy; memoryRead/memoryWrite remain finer overrides. */
    val memoryCache: Boolean? = null,
    /** Weak index override, independent of the strong LRU. Null inherits cachePolicy.memory. */
    val weakMemoryCache: Boolean? = null,
    /** Null inherits SvgaDecodeOptions.defaults at load time. */
    val bitmapConfig: Bitmap.Config? = null,
    val skipInvisibleImages: Boolean? = null,
) {
    init { require(width >= 0 && height >= 0); require(!(cacheOnly && refresh)) }
    constructor(source: Any) : this(SvgaSource.from(source))
    internal fun snapshot() = copy(headers = headers.toSortedMap().toMap())
    fun resolveDecodeOptions(defaults: SvgaDecodeOptions = SvgaDecodeOptions.defaults) = SvgaDecodeOptions(
        bitmapConfig ?: defaults.bitmapConfig, skipInvisibleImages ?: defaults.skipInvisibleImages)
    val readsMemory get() = memoryRead ?: memoryCache ?: cachePolicy.memory
    val writesMemory get() = memoryWrite ?: memoryCache ?: cachePolicy.memory
    val usesWeakMemory get() = weakMemoryCache ?: cachePolicy.memory
    val readsDisk get() = diskRead ?: cachePolicy.disk
    val writesDisk get() = diskWrite ?: cachePolicy.disk
}
