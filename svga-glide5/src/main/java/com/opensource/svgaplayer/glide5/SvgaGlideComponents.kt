package com.opensource.svgaplayer.glide5

import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import kotlinx.coroutines.*
import java.util.Collections
import java.util.WeakHashMap

/** Identity is per subscription: engine flights, rather than Glide jobs, merge equivalent requests. */
internal class SvgaGlideModel(val engine: SvgaEngine, val request: SvgaRequest)

internal object SvgaGlideComponents {
    private val registered = Collections.newSetFromMap(WeakHashMap<Glide, Boolean>())

    @Synchronized fun register(glide: Glide) {
        if (registered.add(glide)) {
            glide.registry.append(SvgaGlideModel::class.java, SvgaResource::class.java, Factory())
                .append(SvgaResource::class.java, SvgaResource::class.java, Decoder())
        }
    }

    private class Factory : ModelLoaderFactory<SvgaGlideModel, SvgaResource> {
        override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<SvgaGlideModel, SvgaResource> {
            override fun handles(model: SvgaGlideModel) = true
            override fun buildLoadData(model: SvgaGlideModel, width: Int, height: Int, options: Options) =
                ModelLoader.LoadData(ObjectKey(model), Fetcher(model))
        }
        override fun teardown() {}
    }

    private class Fetcher(private val model: SvgaGlideModel) : DataFetcher<SvgaResource> {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in SvgaResource>) {
            scope.launch {
                val resource = try { model.engine.acquire(model.request) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (isActive) callback.onLoadFailed(e); return@launch }
                ensureActive()
                callback.onDataReady(resource)
            }
        }
        override fun cancel() { scope.cancel() }
        override fun cleanup() { scope.cancel() }
        override fun getDataClass() = SvgaResource::class.java
        override fun getDataSource() = DataSource.LOCAL
    }

    private class Decoder : ResourceDecoder<SvgaResource, SvgaResource> {
        override fun handles(source: SvgaResource, options: Options) = true
        override fun decode(source: SvgaResource, width: Int, height: Int, options: Options) = object : Resource<SvgaResource> {
            override fun get() = source
            override fun getResourceClass() = SvgaResource::class.java
            override fun getSize() = source.sizeBytes.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            // These immutable pixels are shared with engine caches and other playback instances.
            override fun recycle() {}
        }
    }
}
