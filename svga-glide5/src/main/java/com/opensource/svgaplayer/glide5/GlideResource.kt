package com.opensource.svgaplayer.glide5

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keep Glide's lease until consumption completes, then clear even on cancellation or failure. */
internal suspend fun <T : Any, R> withGlideResource(manager: RequestManager, builder: RequestBuilder<T>,
    consume: suspend (T) -> R): R = withContext(Dispatchers.Main.immediate) {
    var target: CustomTarget<T>? = null
    val handler = Handler(Looper.getMainLooper())
    try {
        val resource = suspendCancellableCoroutine<T> { continuation ->
            val listener = object : RequestListener<T> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<T>, isFirstResource: Boolean): Boolean {
                    handler.post {
                        if (continuation.isActive) continuation.resumeWithException(e ?: GlideException("Glide load failed"))
                    }
                    return false
                }
                override fun onResourceReady(resource: T, model: Any, target: Target<T>?, dataSource: DataSource,
                    isFirstResource: Boolean) = false
            }
            val pending = object : CustomTarget<T>() {
                override fun onResourceReady(resource: T, transition: Transition<in T>?) {
                    // Resuming Main.immediate inline would let finally clear inside Glide's callback.
                    handler.post { if (continuation.isActive) continuation.resume(resource) }
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    handler.post { if (continuation.isActive) continuation.cancel(CancellationException("Glide request cleared")) }
                }
            }
            target = pending
            builder.addListener(listener).into(pending)
        }
        consume(resource)
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) { target?.let(manager::clear) }
    }
}
