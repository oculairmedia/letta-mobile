package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Handles appending local events from external transport (admin-shim WS),
 * marking them as sent/failed, and doing agent-specific reconciliation.
 */
internal class TimelineExternalTransportAppender(
    private val conversationId: String,
    private val messageApi: MessageApi,
    private val eventQueue: Channel<TimelineGatewayEvent>,
    private val state: MutableStateFlow<Timeline>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val writeMutex: Mutex,
    private val pendingLocalStore: PendingLocalStore,
    private val submitReconcileAfterSendSnapshot: suspend (String, List<LettaMessage>) -> ReconcileAfterSendResult,
) {
    suspend fun appendExternalTransportLocal(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val sentAt = Instant.now()
        val ack = CompletableDeferred<String>()
        eventQueue.send(
            TimelineGatewayEvent.ExternalTransportLocalAppend(
                content = content,
                otid = otid,
                attachments = attachments,
                sentAt = sentAt,
                ack = ack,
            )
        )
        return ack.await()
    }

    suspend fun applyExternalTransportLocalAppend(
        event: TimelineGatewayEvent.ExternalTransportLocalAppend,
    ) {
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = state.value.nextLocalPosition(),
                otid = event.otid,
                content = event.content,
                role = Role.USER,
                sentAt = event.sentAt,
                deliveryState = DeliveryState.SENDING,
                attachments = event.attachments,
                source = MessageSource.LETTA_SERVER,
            )
            state.value = state.value.append(local)
        }
        events.emit(TimelineSyncEvent.LocalAppended(event.otid))
        Telemetry.event(
            "TimelineSync", "send.externalTransportLocalAppended",
            "otid" to event.otid,
            "conversationId" to conversationId,
            "contentLength" to event.content.length,
        )
        event.ack.complete(event.otid)
    }

    suspend fun markExternalTransportLocalSent(otid: String) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkSent(otid, ack))
        ack.await()
    }

    suspend fun markExternalTransportLocalFailed(otid: String) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkFailed(otid, ack))
        ack.await()
    }

    suspend fun reconcileExternalTransportSend(
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        val timer = Telemetry.startTimer("TimelineSync", "reconcile")
        try {
            val serverMessages = listAgentMessagesWithRetry(
                agentId = agentId,
                externalConversationId = externalConversationId,
                otid = otid,
            ).reversed()
            val result = submitReconcileAfterSendSnapshot(otid, serverMessages)
            result.confirmedServerId?.let { serverId ->
                events.emit(TimelineSyncEvent.LocalConfirmed(otid, serverId))
            }
            if (result.shouldDeletePendingLocal) {
                runCatching { pendingLocalStore.delete(otid) }
            }
            timer.stop(
                "otid" to otid,
                "serverCount" to serverMessages.size,
                "confirmedLocal" to result.confirmedLocal,
                "appendedMissing" to result.appendedMissing,
            )
        } catch (t: Throwable) {
            timer.stopError(t, "otid" to otid)
            events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
        }
    }

    private suspend fun listAgentMessagesWithRetry(
        agentId: String,
        externalConversationId: String,
        otid: String,
    ): List<LettaMessage> {
        var lastError: Throwable? = null
        for (attempt in 0 until RECONCILE_RETRY_ATTEMPTS) {
            try {
                return messageApi.listMessages(
                    agentId = AgentId(agentId),
                    limit = RECONCILE_LIMIT,
                    order = "desc",
                    conversationId = ConversationId(externalConversationId),
                )
            } catch (t: Throwable) {
                if (!isRetryableReconcileError(t) || attempt == RECONCILE_RETRY_ATTEMPTS - 1) {
                    throw t
                }
                lastError = t
                Telemetry.error(
                    "TimelineSync", "reconcile.ws.retry", t,
                    "otid" to otid,
                    "agentId" to agentId,
                    "conversationId" to externalConversationId,
                    "attempt" to attempt + 1,
                )
                delay(RECONCILE_RETRY_BACKOFF_MS shl attempt)
            }
        }
        throw lastError ?: IllegalStateException("listAgentMessagesWithRetry exhausted without error")
    }

    companion object {
        private const val RECONCILE_LIMIT = 250
        private const val RECONCILE_RETRY_ATTEMPTS = 3
        private const val RECONCILE_RETRY_BACKOFF_MS = 200L
    }
}
