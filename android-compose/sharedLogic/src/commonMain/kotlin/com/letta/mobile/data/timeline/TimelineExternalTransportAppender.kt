package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

import kotlin.time.Duration.Companion.milliseconds
/**
 * Handles appending local events from external transport (admin-shim WS),
 * marking them as sent/failed, and doing agent-specific reconciliation.
 */
class TimelineExternalTransportAppender(
    private val conversationId: String,
    private val messageApi: TimelineTransport,
    private val eventQueue: Channel<TimelineGatewayEvent>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val processor: TimelineProcessor,
    private val pendingLocalStore: PendingLocalStore,
    private val submitReconcileAfterSendSnapshot: suspend (String, List<LettaMessage>) -> ReconcileAfterSendResult,
) {
    suspend fun appendExternalTransportLocal(
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): String {
        val sentAt = timelineNow()
        val ack = CompletableDeferred<String>()
        eventQueue.send(
            TimelineGatewayEvent.ExternalTransportLocalAppend(
                content = content,
                otid = otid,
                attachments = attachments.toTimelinePersistentList(),
                sentAt = sentAt,
                ack = ack,
            )
        )
        return ack.await()
    }

    suspend fun applyExternalTransportLocalAppend(
        event: TimelineGatewayEvent.ExternalTransportLocalAppend,
    ) {
        val result = processor.submit(
            TimelineMutation.LocalAppend(
                pending = PendingSend(event.otid, event.content, event.attachments),
                sentAt = event.sentAt,
                mode = TimelineLocalAppendMode.EXTERNAL_TRANSPORT,
            ),
        ).appliedResultOrThrow()
        Telemetry.event(
            "TimelineSync", "send.externalTransportLocalAppended",
            "otid" to event.otid,
            "conversationId" to conversationId,
            "contentLength" to event.content.length,
            "changed" to result.changed,
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        val request = ReconcileRequest(agentId, externalConversationId, otid)
        repeat(RECONCILE_RETRY_ATTEMPTS) { attempt ->
            val result = fetchAgentMessages(request)
            result.getOrNull()?.let { return it }
            val failure = checkNotNull(result.exceptionOrNull())
            if (!shouldRetryReconcile(failure, attempt)) throw failure
            logReconcileRetry(failure, request, attempt)
            delay((RECONCILE_RETRY_BACKOFF_MS shl attempt).milliseconds)
        }
        error("reconcile retry loop exhausted")
    }

    private suspend fun fetchAgentMessages(
        request: ReconcileRequest,
    ): Result<List<LettaMessage>> = try {
        Result.success(
            messageApi.listAgentMessages(
                agentId = request.agentId,
                limit = RECONCILE_LIMIT,
                order = "desc",
                conversationId = request.externalConversationId,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private fun shouldRetryReconcile(failure: Throwable, attempt: Int): Boolean =
        isRetryableReconcileError(failure) && attempt < RECONCILE_RETRY_ATTEMPTS - 1

    private fun logReconcileRetry(
        failure: Throwable,
        request: ReconcileRequest,
        attempt: Int,
    ) {
        Telemetry.error(
            "TimelineSync", "reconcile.ws.retry", failure,
            "otid" to request.otid,
            "agentId" to request.agentId,
            "conversationId" to request.externalConversationId,
            "attempt" to attempt + 1,
        )
    }

    private data class ReconcileRequest(
        val agentId: String,
        val externalConversationId: String,
        val otid: String,
    )

    companion object {
        private const val RECONCILE_LIMIT = 250
        private const val RECONCILE_RETRY_ATTEMPTS = 3
        private const val RECONCILE_RETRY_BACKOFF_MS = 200L
    }
}
