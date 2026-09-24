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

    private companion object {
        const val FIXTURE_EMITTED_AT = "2026-09-24T00:00:00Z"
    }
}

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

    fun emitUpdateQueue(update: QueueUpdateFixture) = emit(frames.updateQueue(update))

    override fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = frame.rawJson()),
        )
    }
}

private fun AppServerCommand.Input.isApprovalResponse(): Boolean = payload is AppServerInputPayload.ApprovalResponse

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

internal fun List<RuntimeEventDraft>.approvalCards(): Int = count { it.payload is RuntimeEventPayload.ApprovalRequested }

internal fun AppServerCommand.Input.approvalRequestId(): String? =
    (payload as? AppServerInputPayload.ApprovalResponse)?.requestId

internal fun AppServerCommand.Input.approvalDecision(): AppServerApprovalResponseDecision? =
    (payload as? AppServerInputPayload.ApprovalResponse)?.decision
