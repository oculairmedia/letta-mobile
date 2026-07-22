package com.letta.mobile.data.transport.appserver

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One generation-local request registry + inbound router for the App Server
 * control channel (letta-mobile-lgns8.3).
 *
 * Replaces the per-request [AppServerRequestCorrelator] (which spawned one
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
    private val pending = LinkedHashMap<String, PendingRequest<*>>()
    private var collectorJob: Job? = null
    private var failed = false

    /** Start routing control responses. Must be called once per generation. */
    fun startRouting(scope: CoroutineScope) {
        check(collectorJob == null) { "already routing" }
        collectorJob = scope.launch {
            controlFrames.collect { received ->
                val frame = received.frame
                val requestId = frame.requestId ?: return@collect
                val entry = synchronized(lock) { pending[requestId] }
                if (entry != null && entry.complete(frame)) {
                    synchronized(lock) { pending.remove(requestId) }
                }
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
    ): T = coroutineScope {
        val deferred = CompletableDeferred<T>()
        val entry = PendingRequest(deferred, response)
        synchronized(lock) {
            check(!failed) { "generation already failed" }
            check(requestId !in pending) {
                "duplicate request_id: $requestId"
            }
            pending[requestId] = entry
        }

        try {
            send()
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: CancellationException) {
            synchronized(lock) { pending.remove(requestId) }
            throw e
        } catch (e: Exception) {
            synchronized(lock) { pending.remove(requestId) }
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw AppServerRequestTimeoutException(requestId, timeoutMs, e)
            }
            throw e
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

    private class PendingRequest<T : AppServerInboundFrame>(
        private val deferred: CompletableDeferred<T>,
        private val response: (AppServerInboundFrame) -> T?,
    ) {
        fun complete(frame: AppServerInboundFrame): Boolean {
            val typed = response(frame)
            return if (typed != null) {
                deferred.complete(typed)
                true
            } else {
                false
            }
        }

        fun fail(cause: Throwable) {
            if (deferred.isActive) {
                deferred.completeExceptionally(cause)
            }
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
