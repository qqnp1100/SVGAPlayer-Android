package com.opensource.svgaplayer.glide5

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaDownloadProgress
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import kotlinx.coroutines.*

/** Glide 5 adapter. SvgaEngine owns SVGA caching; Glide owns dynamic-image caching. */
class SvgaImageLoader(context: Context, val engine: SvgaEngine = SvgaEngine.get(context)) : AutoCloseable {
    private val lifetime = SupervisorJob()
    val imageLoader: RequestManager = Glide.with(context.applicationContext)

    init { SvgaGlideComponents.register(Glide.get(context.applicationContext)) }

    suspend fun load(request: SvgaRequest,
        onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = request.onDownloadProgress): SvgaResource = operation {
        val options = request.resolveDecodeOptions()
        val snapshot = request.copy(headers = request.headers.toMap(), onDownloadProgress = onDownloadProgress,
            bitmapConfig = options.bitmapConfig, skipInvisibleImages = options.skipInvisibleImages)
        val builder = imageLoader.`as`(SvgaResource::class.java).load(SvgaGlideModel(engine, snapshot))
            .skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE).onlyRetrieveFromCache(false).dontTransform()
        withGlideResource(imageLoader, builder) { it }
    }

    /** Download only by default, preserving the engine's progress and cancellation semantics. */
    suspend fun preDownload(request: SvgaRequest, parseAfterDownload: Boolean = false,
        onDownloadProgress: (suspend (SvgaDownloadProgress) -> Unit)? = request.onDownloadProgress) = operation {
        engine.preDownload(request, parseAfterDownload, onDownloadProgress)
    }

    private suspend fun <T> operation(block: suspend () -> T): T = coroutineScope {
        check(lifetime.isActive) { "Loader closed" }
        val subscription = lifetime.invokeOnCompletion { cancel(CancellationException("Loader closed")) }
        try { block() } finally { subscription.dispose() }
    }

    /** Cancel this adapter's requests without shutting down the application's Glide or shared engine. */
    override fun close() { lifetime.cancel() }

    companion object {
        @Volatile private var shared: SvgaImageLoader? = null
        fun get(context: Context): SvgaImageLoader = shared?.takeIf { it.lifetime.isActive } ?: synchronized(this) {
            shared?.takeIf { it.lifetime.isActive } ?: SvgaImageLoader(context).also { shared = it }
        }
    }
}
