package com.opensource.svgaplayer.loader

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Cache contents can disappear while the process and its engine are still alive. */
internal fun createTemporarySvgaFile(prefix: String, directory: File): File {
    ensureSvgaDirectory(directory)
    return File.createTempFile(prefix, ".svga", directory)
}

internal fun ensureSvgaDirectory(directory: File) {
    // Another request may create the directory between the first check and mkdirs().
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Unable to create SVGA staging directory: " + directory.absolutePath)
    }
}

/** Packaged assets may be reused across engines, but never across different APK installations. */
@Suppress("DEPRECATION")
internal fun installedAssetVersion(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    val application = context.applicationInfo
    val apks = (listOfNotNull(application.sourceDir) + application.splitSourceDirs.orEmpty()).distinct().sorted()
    require(apks.isNotEmpty())
    val parts = listOf(context.packageName, version.toString(), info.lastUpdateTime.toString()) + apks.map { path ->
        val file = File(path).canonicalFile
        require(file.isFile)
        "${file.path}:${file.length()}:${file.lastModified()}"
    }
    MessageDigest.getInstance("SHA-256").digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
}.getOrNull()
