package com.opensource.svgaplayer.loader

import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.MovieParams
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun zipMovie(frames: Int = 2, padding: Int = 0, binary: Boolean = false): ByteArray =
    ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(if (binary) "movie.binary" else "movie.spec"))
            zip.write(if (binary) MovieEntity.ADAPTER.encode(MovieEntity("2.0", MovieParams(100f, 80f, 20, frames),
                emptyMap(), emptyList(), emptyList())) else
                """{"movie":{"viewBox":{"width":100,"height":80},"fps":20,"frames":$frames},"images":{},"sprites":[]}""".toByteArray())
            zip.closeEntry()
            if (padding > 0) {
                zip.putNextEntry(ZipEntry("padding.bin")); zip.write(ByteArray(padding)); zip.closeEntry()
            }
        }
    }.toByteArray()

internal fun zipBinaryMovie(binary: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("movie.binary")); zip.write(binary); zip.closeEntry()
    }
}.toByteArray()
