package com.opensource.svgaplayer.loader

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile

/** Each waiter owns a subscription; only the last departure cancels the producer. */
internal class SharedFlights<K, V>(private val scope: CoroutineScope, private val dispose: (V) -> Unit = {}) {
    private data class ProgressUpdate(val value: SvgaDownloadProgress? = null, val finished: Boolean = false)
    private class Flight<V> {
        lateinit var task: Deferred<V>
        val progress = MutableStateFlow(ProgressUpdate())
        var users = 0
        var result: V? = null
        var disposed = false
    }
    private val flights = HashMap<K, Flight<V>>()
    private fun releaseResult(flight: Flight<V>) {
        if (!flight.disposed && flight.users == 0 && flight.result != null) {
            flight.disposed = true
            @Suppress("UNCHECKED_CAST") dispose(flight.result as V)
            flight.result = null
        }
    }
    suspend fun <R> use(key: K, create: suspend () -> V, consume: suspend (V) -> R): R =
        useWithProgress(key, null, { create() }, consume)

    suspend fun <R> useWithProgress(
        key: K,
        onProgress: (suspend (SvgaDownloadProgress) -> Unit)?,
        create: suspend ((SvgaDownloadProgress) -> Unit) -> V,
        consume: suspend (V) -> R,
    ): R {
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                Flight<V>().also { f ->
                    f.task = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val result = create { f.progress.value = ProgressUpdate(it) }
                            synchronized(flights) { f.result = result; releaseResult(f) }
                            result
                        } finally {
                            f.progress.value = f.progress.value.copy(finished = true)
                        }
                    }
                }
            }.also { it.users++ }
        }
        try { return coroutineScope {
            var delivered: SvgaDownloadProgress? = null
            val observer = onProgress?.let { callback ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    flight.progress.takeWhile { update ->
                        update.value?.let { if (it != delivered) { callback(it); delivered = it } }
                        !update.finished
                    }.collect {}

                }
            }
            try {
                val result = flight.task.await()
                // Drain the final snapshot without interrupting a suspending callback.
                observer?.join()
                consume(result)
            } finally { observer?.cancel() }
        } }
        finally { synchronized(flights) {
            if (--flight.users == 0) {
                if (flights[key] === flight) flights.remove(key)
                flight.task.cancel()
                releaseResult(flight)
            }
        } }
    }
}
