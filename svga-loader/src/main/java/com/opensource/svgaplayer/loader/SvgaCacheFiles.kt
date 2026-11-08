package com.opensource.svgaplayer.loader

import java.io.File
import java.io.IOException

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
