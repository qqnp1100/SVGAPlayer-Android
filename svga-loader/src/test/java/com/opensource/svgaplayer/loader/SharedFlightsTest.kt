package com.opensource.svgaplayer.loader

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedFlightsTest {
    @Test fun oneCancelledSubscriberDoesNotCancelOthers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val disposed = AtomicInteger()
        val flights = SharedFlights<String, String>(scope) { disposed.incrementAndGet() }
        val started = CompletableDeferred<Unit>(); val ready = CompletableDeferred<Unit>()
        val creates = AtomicInteger()
        val create: suspend () -> String = { creates.incrementAndGet(); started.complete(Unit); ready.await(); "ok" }
        val first = async(start = CoroutineStart.UNDISPATCHED) { flights.use("a", create) { it } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { flights.use("a", create) { it } }
        first.cancelAndJoin(); ready.complete(Unit)
        assertEquals("ok", second.await()); assertEquals(1, creates.get()); assertEquals(1, disposed.get())
        scope.cancel()
    }
    @Test fun lastCancellationStopsProducerAndRetryWorks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val flights = SharedFlights<String, String>(scope)
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val first = async { flights.use("a", { try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) } }) { it } }
        started.await(); first.cancelAndJoin(); withTimeout(2000) { stopped.await() }
        assertEquals("retry", flights.use("a", { "retry" }) { it }); scope.cancel()
    }
    @Test fun progressIsReplayedToLateWaiterAndCancellationUnsubscribes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val flights = SharedFlights<String, String>(scope)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val firstProgress = mutableListOf<SvgaDownloadProgress>()
        val secondProgress = mutableListOf<SvgaDownloadProgress>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            flights.useWithProgress("progress", { firstProgress.add(it); started.complete(Unit) }, { emit ->
                emit(SvgaDownloadProgress(10, 20)); finish.await()
                emit(SvgaDownloadProgress(20, 20, true)); "ok"
            }, { it })
        }
        withTimeout(2000) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            flights.useWithProgress("progress", { secondProgress.add(it) }, { error("must share") }, { it })
        }
        assertEquals(listOf(SvgaDownloadProgress(10, 20)), secondProgress)
        first.cancelAndJoin(); val count = firstProgress.size
        finish.complete(Unit)
        assertEquals("ok", second.await())
        assertEquals(SvgaDownloadProgress(20, 20, true), secondProgress.last())
        assertEquals(count, firstProgress.size)
        scope.cancel()
    }

    @Test fun fastProducerDeliversCompletionBeforeReturn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val flights = SharedFlights<String, String>(scope)
        val events = mutableListOf<SvgaDownloadProgress>()
        flights.useWithProgress("fast", { events.add(it) }, { emit ->
            emit(SvgaDownloadProgress(12, null, true)); "ok"
        }, { it })
        assertTrue(events.last().completed); assertNull(events.last().fraction)
        scope.cancel()
    }
    @Test fun producerCompletionDoesNotInterruptSuspendingCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val flights = SharedFlights<String, String>(scope)
        val entered = CompletableDeferred<Unit>()
        var invocations = 0; var completedCallbacks = 0
        flights.useWithProgress("suspend", {
            invocations++; entered.complete(Unit); delay(50); completedCallbacks++
        }, { emit ->
            emit(SvgaDownloadProgress(1, 1, true)); entered.await(); "ok"
        }, { it })
        assertEquals(1, invocations); assertEquals(1, completedCallbacks)
        scope.cancel()
    }
}
