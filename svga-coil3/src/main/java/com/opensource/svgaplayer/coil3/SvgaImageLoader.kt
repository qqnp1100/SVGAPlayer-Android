package com.opensource.svgaplayer.coil3

import android.content.Context
import android.graphics.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.*
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.*

/** A prepared resource, with no View, dynamic bindings or playback state. */
class SvgaLoadedImage(val resource: SvgaResource) : Image {
    override val width get() = resource.width
    override val height get() = resource.height
    override val size get() = resource.sizeBytes
    override val shareable = false
    override fun draw(canvas: Canvas) { resource.newRenderer().use { it.draw(canvas, width, height, 0, 0) } }
}

class SvgaFetcher private constructor(private val engine: SvgaEngine, private val request: SvgaRequest) : Fetcher {
    override suspend fun fetch() = ImageFetchResult(SvgaLoadedImage(engine.acquire(request)), false, DataSource.MEMORY)
    class Factory(private val engine: SvgaEngine) : Fetcher.Factory<SvgaRequest> {
        override fun create(data: SvgaRequest, options: Options, imageLoader: ImageLoader) = SvgaFetcher(engine, data)
    }
}

/** Own ImageLoader; does not replace the host application's Coil singleton. */
class SvgaImageLoader(context: Context, val engine: SvgaEngine = SvgaEngine.get(context)) : AutoCloseable {
    private val context = context.applicationContext
    val imageLoader = ImageLoader.Builder(this.context).components { add(SvgaFetcher.Factory(engine)) }.build()
    suspend fun load(request: SvgaRequest, onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = request.onDownloadProgress): SvgaResource {
        val result = imageLoader.execute(ImageRequest.Builder(context).data(request.copy(headers = request.headers.toMap(), onDownloadProgress = onDownloadProgress))
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
        return when (result) {
            is SuccessResult -> (result.image as SvgaLoadedImage).resource
            is ErrorResult -> throw result.throwable
        }
    }
    override fun close() = imageLoader.shutdown()
    companion object {
        @Volatile private var shared: SvgaImageLoader? = null
        fun get(context: Context): SvgaImageLoader = shared ?: synchronized(this) {
            shared ?: SvgaImageLoader(context).also { shared = it }
        }
    }
}
