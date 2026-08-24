package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Persists runtime audit records outside the coordinator's lifecycle lock.
 *
 * The channel preserves ingress order while opportunistically batching records
 * that are already waiting. Sink failures are isolated so a telemetry outage
 * cannot stop chat event processing; cancellation still propagates normally.
 */
internal class RuntimeEventBatcher(
    scope: CoroutineScope,
    private val persist: suspend (List<ScopedRuntimeEvent>) -> Unit,
) {
    private val records = Channel<ScopedRuntimeEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                drain()
            } finally {
                records.cancel()
            }
        }
    }

    fun enqueue(event: WsTimelineEvent, conversationId: String?) {
        val result = records.trySend(ScopedRuntimeEvent(event, conversationId))
        if (result.isFailure) {
            Telemetry.event(
                "AdminChatVM", "runtimeEvent.enqueueRejected",
                "eventType" to (event::class.simpleName ?: ""),
                "conversationId" to (conversationId ?: ""),
            )
        }
    }

    private suspend fun drain() {
        for (first in records) {
            val batch = mutableListOf(first)
            while (true) {
                val next = records.tryReceive().getOrNull() ?: break
                batch += next
            }
            persistBatch(batch)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun persistBatch(batch: List<ScopedRuntimeEvent>) {
        currentCoroutineContext().ensureActive()
        try {
            persist(batch)
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Telemetry.error(
                "AdminChatVM", "runtimeEvent.recordFailed", error,
                "eventType" to (batch.firstOrNull()?.event?.let { it::class.simpleName } ?: ""),
                "conversationId" to (batch.firstOrNull()?.conversationId ?: ""),
                "batchSize" to batch.size,
            )
        }
    }
}

data class ScopedRuntimeEvent(
    val event: WsTimelineEvent,
    val conversationId: String?,
)
