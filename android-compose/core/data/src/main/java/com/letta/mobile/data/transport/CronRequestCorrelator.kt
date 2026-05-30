package com.letta.mobile.data.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages request correlation and timeout coordination for Cron WebSocket RPCs.
 */
internal class CronRequestCorrelator {

    private val pendingCronRequests = ConcurrentHashMap<String, CompletableDeferred<ServerFrame>>()

    /** Generates a fresh UUID-prefixed request_id for cron correlation. */
    fun newCronRequestId(): String = "cron-${UUID.randomUUID()}"

    /**
     * Registers a deferred response for [requestId], invokes [sendFrame] to write to the wire,
     * and suspends until the response arrives or [timeoutMs] elapses.
     */
    suspend fun awaitCronResponse(
        requestId: String,
        sendFrame: () -> Boolean,
        timeoutMs: Long,
    ): ServerFrame {
        val deferred = CompletableDeferred<ServerFrame>()
        val previous = pendingCronRequests.put(requestId, deferred)
        previous?.cancel()
        try {
            val ok = sendFrame()
            if (!ok) {
                pendingCronRequests.remove(requestId, deferred)
                throw IllegalStateException("Cron send failed: sendFrame returned false")
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingCronRequests.remove(requestId, deferred)
            throw e
        } catch (e: Throwable) {
            pendingCronRequests.remove(requestId, deferred)
            throw e
        }
    }

    /**
     * Completes the deferred response associated with [requestId] if it exists.
     * Returns true if a pending request was matched and completed.
     */
    fun completeRequest(requestId: String, response: ServerFrame): Boolean {
        return pendingCronRequests.remove(requestId)?.complete(response) ?: false
    }

    /**
     * Cancels all currently pending cron requests with the specified [reason].
     */
    fun cancelPendingRequests(reason: String) {
        val snapshot = pendingCronRequests.keys.toList()
        snapshot.forEach { requestId ->
            pendingCronRequests.remove(requestId)?.cancel(CancellationException(reason))
        }
    }
}
