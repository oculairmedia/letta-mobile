package com.letta.mobile.data.transport.appserver

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * One generation-local request registry + inbound router for the App Server
 * control channel.
 *
 * Replaces the former per-request `AppServerRequestCorrelator` (which spawned one
 * `collect` coroutine per call) with a single long-lived collector that routes
 * every control response to the registered [PendingRequest] by [requestId].
 *
 * Lifecycle: one registry per connection generation. When the generation ends,
 * call [failAll] to fail every pending request immediately — no request is left
 * waiting for its individual timeout.
 */
internal class AppServerRequestRegistry(
    private val controlFrames: Flow<AppServerReceivedFrame>,
    private val timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    private val lock = SynchronizedObject()
    private val pending = LinkedHashMap<String, Pending>()
    private var collectorJob: Job? = null
    private var failed = false

    /**
     * Start a single long-lived collector that routes every control response by
     * request_id to the registered pending entry. Optional: when not called,
     * [request] launches a per-call collector for the duration of that request
     * (still correct — all entries share the ONE registry map).
     */
    fun startRouting(scope: CoroutineScope) {
        check(collectorJob == null) { "already routing" }
        // Enter collect before returning so an immediate request/response cannot
        // outrun this SharedFlow subscriber during client construction.
        collectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { routeFrames() }
    }

    /**
     * This collector never suspends per frame (a map lookup under a
     * lock, then [Pending.offer], which completes a deferred or `trySend`s into an unlimited
     * channel). It reads the control VIEW, whose queue is separate from the ordered queue
     * the router reads, so it cannot hold up runtime event delivery either way.
     */
    private suspend fun routeFrames() {
        controlFrames.collect { received ->
            val frame = received.frame
            val requestId = frame.requestId ?: return@collect
            val entry = synchronized(lock) { pending[requestId]?.takeUnless { it.responseComplete } }
            // Keep the entry until its caller exits: a response may arrive while
            // send is still suspended, and failAll must still be able to wake it.
            if (entry != null && entry.offer(frame)) {
                synchronized(lock) { entry.responseComplete = true }
            }
        }
    }

    /**
     * Register a pending request before enqueueing the send, then await the
     * correlated response (or timeout / cancellation).
     *
     * @throws IllegalStateException if [requestId] is already registered.
     * @throws AppServerRequestTimeoutException on timeout.
     * @throws AppServerRequestFailedException if the generation fails first.
     */
    suspend fun <T : AppServerInboundFrame> request(
        requestId: String,
        response: (AppServerInboundFrame) -> T?,
        send: suspend () -> Unit,
    ): T {
        val pending = PendingRequest(CompletableDeferred<T>(), response)
        return awaitRegistered(requestId, pending) {
            withTimeout(timeoutMs.milliseconds) {
                sendRegistered(pending, send)
                pending.result()
            }
        }
    }

    /**
     * A request the server answers with several frames sharing one request_id, the last of
     * which [isFinal] recognises (`list_memory` pages carry `done`).
     * Returns every matched frame in arrival order. [timeoutMs] bounds send independently,
     * then bounds the wait for EACH response frame, so a long
     * listing that keeps producing pages is never cut off, while a stalled one surfaces
     * [AppServerRequestTimeoutException] instead of a partial result.
     */
    suspend fun <T : AppServerInboundFrame> requestStream(
        requestId: String,
        response: (AppServerInboundFrame) -> T?,
        isFinal: (T) -> Boolean,
        send: suspend () -> Unit,
    ): List<T> {
        val entry = PendingStream(response, isFinal)
        return awaitRegistered(requestId, entry) {
            withTimeout(timeoutMs.milliseconds) { sendRegistered(entry, send) }
            entry.collectStream()
        }
    }

    private suspend fun <T : AppServerInboundFrame> PendingStream<T>.collectStream(): List<T> =
        buildList {
            while (true) {
                val next = withTimeout(timeoutMs.milliseconds) {
                    kotlinx.coroutines.selects.select {
                        failure.onAwait { throw it }
                        frames.onReceiveCatching { it }
                    }
                }
                next.exceptionOrNull()?.let { throw it }
                add(next.getOrNull() ?: break)
            }
        }

    private suspend fun <T> awaitRegistered(
        requestId: String,
        entry: Pending,
        await: suspend () -> T,
    ): T = coroutineScope {
        synchronized(lock) {
            check(!failed) { "generation already failed" }
            check(requestId !in pending) {
                "duplicate request_id: $requestId"
            }
            pending[requestId] = entry
        }

        // When no long-lived router is active, route frames for this request's
        // lifetime only. Cancelled in finally so no collector leaks.
        val localCollector = if (collectorJob == null) launch { routeFrames() } else null

        try {
            await()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Must precede the CancellationException clause below:
            // TimeoutCancellationException IS a CancellationException, and
            // callers rely on the typed timeout wrapper.
            // Only wrap when THIS request's own withTimeout(timeoutMs) fired. A
            // PARENT cancellation (e.g. the native-admin breaker's short
            // withTimeout, or the caller's scope being cancelled) also surfaces
            // here as a TimeoutCancellationException; ensureActive() rethrows that
            // parent cancellation so it propagates as cancellation instead of being
            // mislabeled as this request's `timeoutMs` timeout (and silently
            // converting a CancellationException into a plain Exception).
            val parentCancellation = try {
                ensureActive()
                null
            } catch (parentCancellation: kotlinx.coroutines.TimeoutCancellationException) {
                parentCancellation
            }
            if (parentCancellation != null) throw parentCancellation
            throw AppServerRequestTimeoutException(requestId, timeoutMs, e)
        } finally {
            synchronized(lock) { pending.remove(requestId) }
            localCollector?.cancel()
        }
    }

    private suspend fun sendRegistered(entry: Pending, send: suspend () -> Unit) = supervisorScope {
        // Supervision keeps a send exception from cancelling the request scope before
        // its timeout/cancellation classification. Teardown must also wake a blocked send.
        val sender = async { send() }
        try {
            select<Unit> {
                entry.failure.onAwait { throw it }
                sender.onAwait { }
            }
        } finally {
            sender.cancel()
        }
    }

    /**
     * Fail every pending request immediately. Idempotent; safe to call on every
     * generation teardown path (disconnect, cancel, close).
     */
    fun failAll(cause: Throwable) {
        val snapshot = synchronized(lock) {
            if (failed) return
            failed = true
            pending.values.toList().also { pending.clear() }
        }
        val exception = AppServerRequestFailedException(cause)
        for (entry in snapshot) {
            entry.fail(exception)
        }
    }

    /** Cancel the collector and fail remaining pendings. */
    fun cancel() {
        collectorJob?.cancel()
        failAll(CancellationException("registry cancelled"))
    }

    /** One registered request_id awaiting its response frame(s). */
    private interface Pending {
        val failure: CompletableDeferred<Throwable>
        var responseComplete: Boolean

        /** Offers a frame carrying this request_id; true once the request has its last frame. */
        fun offer(frame: AppServerInboundFrame): Boolean

        fun fail(cause: Throwable)
    }

    private class PendingRequest<T : AppServerInboundFrame>(
        private val deferred: CompletableDeferred<T>,
        private val response: (AppServerInboundFrame) -> T?,
    ) : Pending {
        suspend fun result(): T = deferred.await()

        override val failure = CompletableDeferred<Throwable>()
        override var responseComplete = false

        override fun offer(frame: AppServerInboundFrame): Boolean {
            val typed = response(frame)
            return if (typed != null) {
                deferred.complete(typed)
                true
            } else {
                false
            }
        }

        override fun fail(cause: Throwable) {
            failure.complete(cause)
            if (deferred.isActive) {
                deferred.completeExceptionally(cause)
            }
        }
    }

    private class PendingStream<T : AppServerInboundFrame>(
        private val response: (AppServerInboundFrame) -> T?,
        private val isFinal: (T) -> Boolean,
    ) : Pending {
        override val failure = CompletableDeferred<Throwable>()
        override var responseComplete = false

        val frames = Channel<T>(Channel.UNLIMITED)

        override fun offer(frame: AppServerInboundFrame): Boolean {
            val typed = response(frame) ?: return false
            frames.trySend(typed)
            val last = isFinal(typed)
            if (last) frames.close()
            return last
        }

        override fun fail(cause: Throwable) {
            failure.complete(cause)
            frames.close(cause)
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 30_000L
    }
}

class AppServerRequestTimeoutException(
    val requestId: String,
    val timeoutMs: Long,
    cause: Throwable,
) : Exception("request $requestId timed out after ${timeoutMs}ms", cause)

class AppServerRequestFailedException(
    cause: Throwable,
) : Exception("request failed: ${cause.message}", cause)
