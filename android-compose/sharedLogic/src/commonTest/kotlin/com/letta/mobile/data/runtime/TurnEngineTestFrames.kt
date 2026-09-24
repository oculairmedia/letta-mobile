package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerQueueRemoval
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Shared stream-frame, `input_accepted` and `update_queue` fixtures for App
 * Server turn-engine tests (letta-mobile-qygvv.1).
 */

internal const val TEST_INPUT_REQUEST_ID = "req-1"

/** What the fake App Server answers to an acknowledged `create_message`. */
internal data class InputAckFixture(
    val accepted: Boolean = true,
    val disposition: String? = null,
    val error: String? = null,
) {
    companion object {
        val Started = InputAckFixture(disposition = "started")
        val Queued = InputAckFixture(disposition = "queued")
        val NoDisposition = InputAckFixture()
        val RejectedWithoutError = InputAckFixture(accepted = false)

        fun rejected(error: String) = InputAckFixture(accepted = false, error = error)
    }
}

/** One `update_queue` snapshot: client message ids still queued plus removal transitions. */
internal data class QueueUpdateFixture(
    val queued: List<String> = emptyList(),
    val removed: List<AppServerQueueRemoval> = emptyList(),
) {
    companion object {
        fun stillQueued(clientMessageId: String) = QueueUpdateFixture(queued = listOf(clientMessageId))

        fun dequeued(clientMessageId: String) =
            QueueUpdateFixture(removed = listOf(AppServerQueueRemoval(clientMessageId, "dequeued")))

        fun cancelled(clientMessageId: String) =
            QueueUpdateFixture(removed = listOf(AppServerQueueRemoval(clientMessageId, "cancelled")))
    }
}

/** Builds sequenced stream-channel frames for one runtime. */
internal class TurnEngineTestFrames(
    private val runtime: AppServerRuntimeScope,
    private val runId: String = "run-1",
) {
    private var seq = 0L

    fun inputAccepted(ack: InputAckFixture) = AppServerInboundFrame.InputAccepted(
        requestId = TEST_INPUT_REQUEST_ID,
        runtime = runtime,
        accepted = ack.accepted,
        disposition = ack.disposition,
        error = ack.error,
    )

    fun streamDelta(messageType: String): AppServerInboundFrame.StreamDelta {
        seq += 1
        return AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = FIXTURE_EMITTED_AT,
            idempotencyKey = "evt-$messageType-$seq",
            delta = buildJsonObject {
                put("message_type", messageType)
                put("run_id", runId)
            },
        )
    }

    fun updateQueue(update: QueueUpdateFixture): AppServerInboundFrame.UpdateQueue {
        seq += 1
        return AppServerInboundFrame.UpdateQueue(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = FIXTURE_EMITTED_AT,
            idempotencyKey = "queue-$seq",
            queue = update.queued.map { id -> buildJsonObject { put("client_message_id", id) } },
            removed = update.removed,
        )
    }

    fun turnFinished(turnId: String, runId: String?): AppServerInboundFrame.TurnFinished {
        seq += 1
        return AppServerInboundFrame.TurnFinished(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = FIXTURE_EMITTED_AT,
            idempotencyKey = "finished-$seq",
            turnId = turnId,
            stopReason = "end_turn",
            runId = runId,
        )
    }

    private companion object {
        const val FIXTURE_EMITTED_AT = "2026-09-24T00:00:00Z"
    }
}

/**
 * Fake App Server that acknowledges `create_message` with [ack], records what was
 * sent acknowledged vs fire-and-forget, and replays emitted frames on the stream channel.
 */
internal class TurnEngineTestAckingClient(
    private val frames: TurnEngineTestFrames,
    private val ack: InputAckFixture,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
    val acknowledgedInputs = mutableListOf<AppServerCommand.Input>()
    val plainInputs = mutableListOf<AppServerCommand.Input>()
    var supportsAck = true
    var ackFailure: Throwable? = null
    var ackGate: CompletableDeferred<AppServerInboundFrame.InputAccepted>? = null

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                agentId = requireNotNull(command.agentId),
                conversationId = requireNotNull(command.conversationId),
            ),
        )

    override suspend fun input(command: AppServerCommand.Input) {
        plainInputs += command
    }

    override suspend fun inputAwaitingAcceptance(
        command: AppServerCommand.Input,
    ): AppServerInboundFrame.InputAccepted {
        if (!supportsAck) throw UnsupportedOperationException("no ack")
        acknowledgedInputs += command
        ackFailure?.let { throw it }
        return ackGate?.await() ?: frames.inputAccepted(ack)
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync unused")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort unused")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        error("adminRpc unused")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

    fun emitStreamDelta(messageType: String) = emit(frames.streamDelta(messageType))

    fun emitUpdateQueue(update: QueueUpdateFixture) = emit(frames.updateQueue(update))

    fun emitTurnFinished(turnId: String, runId: String?) = emit(frames.turnFinished(turnId, runId))

    private fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = frame.rawJson()),
        )
    }
}

private fun AppServerInboundFrame.rawJson(): JsonObject = buildJsonObject {
    put("type", type ?: "unknown")
    when (val frame = this@rawJson) {
        is AppServerInboundFrame.StreamDelta -> {
            put("idempotency_key", frame.idempotencyKey)
            put("delta", frame.delta)
        }
        is AppServerInboundFrame.UpdateQueue -> put("idempotency_key", frame.idempotencyKey)
        else -> Unit
    }
}

internal fun List<RuntimeEventDraft>.lifecycles(): List<RuntimeEventPayload.RunLifecycleChanged> =
    mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }

internal fun List<RuntimeEventDraft>.lastLifecycle(): RuntimeEventPayload.RunLifecycleChanged? =
    lifecycles().lastOrNull()

internal fun List<RuntimeEventDraft>.lifecycleReasons(): List<String> = lifecycles().mapNotNull { it.reason }
