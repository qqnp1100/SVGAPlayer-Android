package com.opensource.svgaplayer.loader

/** HTTP response body progress, independent of decoding and playback.
 * Unknown content length (including transparent gzip) is null. Cached/local loads emit nothing.
 */
data class SvgaDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
    val completed: Boolean = false,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let {
        (bytesRead.toDouble() / it).toFloat().coerceIn(0f, 1f)
    }
}
