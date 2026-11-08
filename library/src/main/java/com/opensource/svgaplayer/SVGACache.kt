package com.opensource.svgaplayer

import android.content.Context
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * SVGA 缓存管理
 */
@Deprecated("Deprecated since 3.0.0. Configure caching with SvgaEngine and SvgaRequest.cachePolicy.")
object SVGACache {
    @Deprecated("Deprecated since 3.0.0. Use SvgaCachePolicy on each SvgaRequest.")
    enum class Type {
        DEFAULT,
        FILE
    }

    private const val TAG = "SVGACache"
    private var type: Type = Type.DEFAULT
    private var cacheDir: String = "/"
        get() {
            if (field != "/") {
                val dir = File(field)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            return field
        }


    @Deprecated("Deprecated since 3.0.0. SvgaEngine initializes its cache automatically.")
    fun onCreate(context: Context?) {
        onCreate(context, Type.DEFAULT)
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaRequest.cachePolicy; SvgaEngine initializes its cache automatically.")
    fun onCreate(context: Context?, type: Type) {
        if (isInitialized()) return
        context ?: return
        cacheDir = "${context.cacheDir.absolutePath}/svga/"
        File(cacheDir).takeIf { !it.exists() }?.mkdirs()
        this.type = type
    }

    /**
     * 清理缓存
     */
    @Deprecated("Deprecated since 3.0.0. Use SvgaEngine.clearMemory and clearDisk; legacy and modern cache directories are separate.")
    fun clearCache() {
        if (!isInitialized()) {
            LogUtils.error(TAG, "SVGACache is not init!")
            return
        }
        SVGAParser.execute({ LogUtils.error(TAG, "Cache cleanup queue is full", it) }) {
            clearDir(cacheDir)
            LogUtils.info(TAG, "Clear svga cache done!")
        }
    }

    // 清除目录下的所有文件
    internal fun clearDir(path: String) {
        try {
            val dir = File(path)
            dir.takeIf { it.exists() }?.let { parentDir ->
                parentDir.listFiles()?.forEach { file ->
                    if (!file.exists()) {
                        return@forEach
                    }
                    if (file.isDirectory) {
                        clearDir(file.absolutePath)
                    }
                    file.delete()
                }
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, "Clear svga cache path: $path fail", e)
        }
    }

    @Deprecated("Deprecated since 3.0.0. SvgaEngine initializes its cache automatically.")
    fun isInitialized(): Boolean {
        return "/" != cacheDir && File(cacheDir).exists()
    }

    @Deprecated("Deprecated since 3.0.0. Read the cachePolicy of your SvgaRequest instead.")
    fun isDefaultCache(): Boolean = type == Type.DEFAULT

    @Deprecated("Deprecated since 3.0.0. Load a SvgaRequest with cacheOnly = true to query the modern cache.")
    fun isCached(cacheKey: String): Boolean {
        val directory = buildCacheDir(cacheKey)
        return buildSvgaFile(cacheKey).isFile || (File(directory, ".complete").isFile &&
            (File(directory, "movie.binary").isFile || File(directory, "movie.spec").isFile))
    }

    @Deprecated("Deprecated since 3.0.0. SvgaEngine manages cache identities from SvgaRequest automatically.")
    fun buildCacheKey(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val digest = messageDigest.digest()
        var sb = ""
        for (b in digest) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    @Deprecated("Deprecated since 3.0.0. SvgaEngine manages cache identities from SvgaRequest automatically.")
    fun buildCacheKey(url: URL): String = buildCacheKey(url.toString())

    @Deprecated("Deprecated since 3.0.0. SvgaEngine manages cache files automatically; these paths refer only to the legacy cache.")
    fun buildCacheDir(cacheKey: String): File {
        return File("$cacheDir$cacheKey/")
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaSource.LocalFile for local files; SvgaEngine manages its own cache files.")
    fun buildSvgaFile(cacheKey: String): File {
        return File("$cacheDir$cacheKey.svga")
    }

    @Deprecated("Deprecated since 3.0.0. Audio files are managed by each SvgaAudioSession.")
    fun buildAudioFile(audio: String): File {
        return File("$cacheDir$audio.mp3")
    }

}
