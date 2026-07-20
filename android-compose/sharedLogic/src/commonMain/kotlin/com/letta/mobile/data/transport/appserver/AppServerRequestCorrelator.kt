package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

import kotlin.time.Duration.Companion.milliseconds
class AppServerRequestCorrelator(
    private val controlFrames: Flow<AppServerReceivedFrame>,
    private val timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    suspend fun <T : AppServerInboundFrame> request(
        requestId: String,
        response: (AppServerInboundFrame) -> T?,
        send: suspend () -> Unit,
    ): T = coroutineScope {
        val deferred = CompletableDeferred<T>()
        val collector = launch {
            controlFrames.collect { received ->
                val frame = received.frame
                if (frame.requestId == requestId) {
                    val typed = response(frame)
                    if (typed != null && !deferred.isCompleted) {
                        deferred.complete(typed)
                    }
                }
            }
        }

        try {
            send()
            withTimeout(timeoutMs.milliseconds) {
                deferred.await()
            }
        } finally {
            collector.cancelAndJoin()
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 30_000L
    }
}
