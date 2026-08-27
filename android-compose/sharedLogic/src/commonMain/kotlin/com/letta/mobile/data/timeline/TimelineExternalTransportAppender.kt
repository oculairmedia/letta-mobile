package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlin.jvm.JvmInline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

import kotlin.time.Duration.Companion.milliseconds
@JvmInline
value class TimelineExternalOtid(val value: String)

data class TimelineExternalAppendRequest(
    val content: String,
    val otid: TimelineExternalOtid,
    val attachments: List<MessageContentPart.Image> = emptyList(),
)

data class TimelineExternalReconcileRequest(
    val agentId: String,
    val externalConversationId: String,
    val otid: TimelineExternalOtid,
)

@JvmInline
private value class ReconcileAttempt(val value: Int)

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
    suspend fun appendExternalTransportLocal(request: TimelineExternalAppendRequest): String {
        val sentAt = timelineNow()
        val ack = CompletableDeferred<String>()
        eventQueue.send(
            TimelineGatewayEvent.ExternalTransportLocalAppend(
                content = request.content,
                otid = request.otid.value,
                attachments = request.attachments.toTimelinePersistentList(),
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

    suspend fun markExternalTransportLocalSent(otid: TimelineExternalOtid) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkSent(otid.value, ack))
        ack.await()
    }

    suspend fun markExternalTransportLocalFailed(otid: TimelineExternalOtid) {
        val ack = CompletableDeferred<Unit>()
        eventQueue.send(TimelineGatewayEvent.MarkFailed(otid.value, ack))
        ack.await()
    }

    suspend fun reconcileExternalTransportSend(request: TimelineExternalReconcileRequest) {
        val timer = Telemetry.startTimer("TimelineSync", "reconcile")
        try {
            val serverMessages = listAgentMessagesWithRetry(request).reversed()
            val result = submitReconcileAfterSendSnapshot(request.otid.value, serverMessages)
            result.confirmedServerId?.let { serverId ->
                events.emit(TimelineSyncEvent.LocalConfirmed(request.otid.value, serverId))
            }
            if (result.shouldDeletePendingLocal) {
                runCatching { pendingLocalStore.delete(request.otid.value) }
            }
            timer.stop(
                "otid" to request.otid.value,
                "serverCount" to serverMessages.size,
                "confirmedLocal" to result.confirmedLocal,
                "appendedMissing" to result.appendedMissing,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            timer.stopError(t, "otid" to request.otid.value)
            events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
        }
    }

    private suspend fun listAgentMessagesWithRetry(
        request: TimelineExternalReconcileRequest,
    ): List<LettaMessage> {
        repeat(RECONCILE_RETRY_ATTEMPTS) { attemptValue ->
            val attempt = ReconcileAttempt(attemptValue)
            val result = fetchAgentMessages(request)
            result.getOrNull()?.let { return it }
            val failure = checkNotNull(result.exceptionOrNull())
            if (!shouldRetryReconcile(failure, attempt)) throw failure
            logReconcileRetry(failure, request, attempt)
            delay((RECONCILE_RETRY_BACKOFF_MS shl attempt.value).milliseconds)
        }
        error("reconcile retry loop exhausted")
    }

    private suspend fun fetchAgentMessages(
        request: TimelineExternalReconcileRequest,
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

    private fun shouldRetryReconcile(failure: Throwable, attempt: ReconcileAttempt): Boolean =
        isRetryableReconcileError(failure) && attempt.value < RECONCILE_RETRY_ATTEMPTS - 1

    private fun logReconcileRetry(
        failure: Throwable,
        request: TimelineExternalReconcileRequest,
        attempt: ReconcileAttempt,
    ) {
        Telemetry.error(
            "TimelineSync", "reconcile.ws.retry", failure,
            "otid" to request.otid.value,
            "agentId" to request.agentId,
            "conversationId" to request.externalConversationId,
            "attempt" to attempt.value + 1,
        )
    }

    companion object {
        private const val RECONCILE_LIMIT = 250
        private const val RECONCILE_RETRY_ATTEMPTS = 3
        private const val RECONCILE_RETRY_BACKOFF_MS = 200L
    }
}
