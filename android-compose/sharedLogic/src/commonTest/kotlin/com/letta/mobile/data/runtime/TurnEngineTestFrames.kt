package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Shared stream-frame, `input_accepted` and approval fixtures for App Server turn-engine tests
 * (letta-mobile-qygvv.1, qygvv.5). The `update_queue` fixture lives beside the shared fake in
 * TurnEngineTestStreamClient.kt.
 */

internal const val TEST_INPUT_REQUEST_ID = "req-1"
internal const val TEST_APPROVAL_REQUEST_ID = "approval-1"
internal const val APPROVAL_NOT_PENDING_ERROR = "Approval request is no longer pending"

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

/** One server queue item (letta-mobile-qygvv.6): its item id, owning client message id, and pause state. */
internal data class QueueItemFixture(
    val id: String,
    val clientMessageId: String,
    val paused: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("client_message_id", clientMessageId)
        put("kind", "message")
        put("source", "user")
        put("content", "queued text")
        put("enqueued_at", FIXTURE_EMITTED_AT)
        if (paused) put("paused", true)
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

    fun streamDelta(messageType: String, run: TestRun = TestRun(runId)): AppServerInboundFrame.StreamDelta {
        seq += 1
        return AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = FIXTURE_EMITTED_AT,
            idempotencyKey = "evt-$messageType-$seq",
            delta = buildJsonObject {
                put("message_type", messageType)
                put("run_id", run.id)
            },
        )
    }

    fun loopStatus(state: TestLoopState): AppServerInboundFrame.UpdateLoopStatus {
        seq += 1
        return state.frame().copy(eventSeq = seq, idempotencyKey = "loop-$seq")
    }

    fun updateQueue(update: QueueUpdateFixture): AppServerInboundFrame.UpdateQueue {
        seq += 1
        return AppServerInboundFrame.UpdateQueue(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = FIXTURE_EMITTED_AT,
            idempotencyKey = "queue-$seq",
            queue = update.queued.map { id -> buildJsonObject { put("client_message_id", id) } } +
                update.items.map(QueueItemFixture::toJson),
            removed = update.removed,
        )
    }

    /** The `can_use_tool` approval gate [TEST_APPROVAL_REQUEST_ID] (letta-mobile-qygvv.5). */
    fun approvalControlRequest() = AppServerInboundFrame.ControlRequest(
        requestId = TEST_APPROVAL_REQUEST_ID,
        request = buildJsonObject {
            put("subtype", "can_use_tool")
            put("tool_name", "searxng_web_search")
            put("tool_call_id", "tool-call-1")
            put("input", buildJsonObject { put("query", "iroh") })
        },
        agentId = runtime.agentId,
        conversationId = runtime.conversationId,
    )

    /** An `approval_request_message` stream delta for `tool-call-1` (letta-mobile-qygvv.13). */
    fun approvalRequestMessage(toolName: String): AppServerInboundFrame.StreamDelta {
        seq += 1
        return AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = FIXTURE_EMITTED_AT,
            idempotencyKey = "evt-approval-$seq",
            delta = buildJsonObject {
                put("message_type", "approval_request_message")
                put("id", "letta-msg-$seq")
                put("run_id", runId)
                put("tool_call", buildJsonObject {
                    put("tool_call_id", "tool-call-1")
                    put("name", toolName)
                    put("arguments", "{}")
                })
            },
        )
    }

    /** [run]'s `turn_finished` for turn number [turn], sequenced after the frames already built. */
    fun turnFinished(run: TestRun, turn: Int): AppServerInboundFrame.TurnFinished {
        seq += 1
        return run.turnFinished(turn).copy(eventSeq = seq)
    }
}

private const val FIXTURE_EMITTED_AT = "2026-09-24T00:00:00Z"

/**
 * Fake App Server that acknowledges inputs with [ack] (approval responses with [approvalAck] when
 * set), records what was sent acknowledged vs fire-and-forget, and replays emitted frames on the
 * stream channel.
 */
internal class TurnEngineTestAckingClient(
    private val frames: TurnEngineTestFrames,
    private val ack: InputAckFixture,
) : TurnEngineTestStreamClient() {
    val acknowledgedInputs = mutableListOf<AppServerCommand.Input>()
    val plainInputs = mutableListOf<AppServerCommand.Input>()
    var supportsAck = true
    var ackFailure: Throwable? = null
    var ackGate: CompletableDeferred<AppServerInboundFrame.InputAccepted>? = null
    var approvalAck: InputAckFixture? = null

    /** Holds the NEXT approval response's ack until completed (letta-mobile-qygvv.5). */
    var approvalAckGate: CompletableDeferred<AppServerInboundFrame.InputAccepted>? = null

    fun approvalInputs() = acknowledgedInputs.filter { it.isApprovalResponse() }

    override suspend fun input(command: AppServerCommand.Input) {
        plainInputs += command
    }

    override suspend fun inputAwaitingAcceptance(
        command: AppServerCommand.Input,
    ): AppServerInboundFrame.InputAccepted {
        if (!supportsAck) throw UnsupportedOperationException("no ack")
        acknowledgedInputs += command
        ackFailure?.let { throw it }
        if (command.isApprovalResponse()) return awaitApprovalAck()
        return ackGate?.await() ?: frames.inputAccepted(ack)
    }

    private suspend fun awaitApprovalAck(): AppServerInboundFrame.InputAccepted {
        val gate = approvalAckGate.also { approvalAckGate = null }
        return gate?.await() ?: frames.inputAccepted(approvalAck ?: ack)
    }

    fun emitStreamDelta(messageType: String) = emit(frames.streamDelta(messageType))

    fun emitStreamDelta(messageType: String, run: TestRun) = emit(frames.streamDelta(messageType, run))

    fun emitUpdateQueue(update: QueueUpdateFixture) = emit(frames.updateQueue(update))

    fun emitTurnFinished(run: TestRun, turn: Int) = emit(frames.turnFinished(run, turn))

    fun emitLoopStatus(state: TestLoopState) = emit(frames.loopStatus(state))

    override fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(frame.onStreamChannel())
    }
}

/** [this] as the stream channel delivers it. */
internal fun AppServerInboundFrame.onStreamChannel(): AppServerReceivedFrame =
    AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = this, raw = rawJson())

private fun AppServerCommand.Input.isApprovalResponse(): Boolean = payload is AppServerInputPayload.ApprovalResponse

private fun AppServerInboundFrame.rawJson(): JsonObject = buildJsonObject {
    put("type", type ?: "unknown")
    when (val frame = this@rawJson) {
        is AppServerInboundFrame.StreamDelta -> {
            put("idempotency_key", frame.idempotencyKey)
            put("delta", frame.delta)
        }
        is AppServerInboundFrame.UpdateQueue -> put("idempotency_key", frame.idempotencyKey)
        is AppServerInboundFrame.TurnFinished -> put("idempotency_key", frame.idempotencyKey)
        is AppServerInboundFrame.UpdateLoopStatus -> put("idempotency_key", frame.idempotencyKey)
        else -> Unit
    }
}

internal fun List<RuntimeEventDraft>.lifecycles(): List<RuntimeEventPayload.RunLifecycleChanged> =
    mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }

internal fun List<RuntimeEventDraft>.lastLifecycle(): RuntimeEventPayload.RunLifecycleChanged? =
    lifecycles().lastOrNull()

internal fun List<RuntimeEventDraft>.lifecycleReasons(): List<String> = lifecycles().mapNotNull { it.reason }

internal fun List<RuntimeEventDraft>.approvalCards(): Int = count { it.payload is RuntimeEventPayload.ApprovalRequested }

internal fun AppServerCommand.Input.approvalRequestId(): String? =
    (payload as? AppServerInputPayload.ApprovalResponse)?.requestId

internal fun AppServerCommand.Input.approvalDecision(): AppServerApprovalResponseDecision? =
    (payload as? AppServerInputPayload.ApprovalResponse)?.decision
