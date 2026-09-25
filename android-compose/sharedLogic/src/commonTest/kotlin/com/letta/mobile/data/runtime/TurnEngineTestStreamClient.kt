package com.letta.mobile.data.runtime

import app.cash.turbine.ReceiveTurbine
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerQueueRemoval
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val turnEngineTerminalStatuses: Set<RuntimeRunStatus> = setOf(
    RuntimeRunStatus.Completed,
    RuntimeRunStatus.Failed,
    RuntimeRunStatus.Cancelled,
)

internal fun RuntimeEventDraft.runLifecycleStatus(): RuntimeRunStatus? =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status

internal suspend fun ReceiveTurbine<RuntimeEventDraft>.awaitTerminalDraft(): RuntimeEventDraft {
    while (true) {
        val item = awaitItem()
        val status = item.runLifecycleStatus() ?: continue
        if (status in turnEngineTerminalStatuses) return item
    }
}

/**
 * One `update_queue` snapshot: client message ids still queued, full queue [items], and
 * removal transitions.
 */
internal data class QueueUpdateFixture(
    val queued: List<String> = emptyList(),
    val removed: List<AppServerQueueRemoval> = emptyList(),
    val items: List<QueueItemFixture> = emptyList(),
) {
    companion object {
        fun stillQueued(clientMessageId: String) = QueueUpdateFixture(queued = listOf(clientMessageId))

        fun dequeued(clientMessageId: String) =
            QueueUpdateFixture(removed = listOf(AppServerQueueRemoval(clientMessageId, "dequeued")))

        fun cancelled(clientMessageId: String) =
            QueueUpdateFixture(removed = listOf(AppServerQueueRemoval(clientMessageId, "cancelled")))

        fun of(vararg items: QueueItemFixture) = QueueUpdateFixture(items = items.toList())
    }
}

/**
 * The one shared fake App Server for turn-engine tests: starts any runtime, swallows sends and
 * replays [emit]ted frames on the stream channel. Tests that need acknowledgement extend it
 * ([TurnEngineTestAckingClient]).
 */
internal open class TurnEngineTestStreamClient : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                agentId = requireNotNull(command.agentId),
                conversationId = requireNotNull(command.conversationId),
            ),
        )

    override suspend fun input(command: AppServerCommand.Input) = Unit

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync unused")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort unused")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true, result = null)

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

    open fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {
                    put("type", frame.type ?: "unknown")
                    put("idempotency_key", "evt-${frame.type}")
                    if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
                },
            ),
        )
    }
}

/** The `create_message` client message id [TurnEngineTestRecordingClient] finishes at once (run `run-9`). */
internal const val AUTO_FINISH_MESSAGE_ID = "auto-finish"

/** The tools a `can_use_tool` control request in the unleased-approval tests asks about. */
internal enum class TestApprovalTool(val wire: String) {
    Bash("Bash"),
    AskUserQuestion("AskUserQuestion"),
    ;

    /** A `can_use_tool` control request (`perm-1`) for this tool on `agent-1/conv-1`. */
    fun controlRequest() = AppServerInboundFrame.ControlRequest(
        requestId = "perm-1",
        request = buildJsonObject {
            put("subtype", "can_use_tool")
            put("tool_name", wire)
            put("tool_call_id", "call-perm-1")
        },
        agentId = "agent-1",
        conversationId = "conv-1",
    )
}

/** [this] frame as the stream channel delivers it. */
internal fun AppServerInboundFrame.onStream(): AppServerReceivedFrame {
    val frame = this
    return AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = frame,
        raw = buildJsonObject {
            put("type", frame.type ?: "unknown")
            put("idempotency_key", "evt-${frame.type}-${frame.requestId}")
            if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
        },
    )
}

/**
 * letta-mobile-qygvv.3: records every abort, sync and input. [syncReplay] is the loop status the
 * App Server replays for a `sync`; null replays nothing. A user input whose client message id is
 * [AUTO_FINISH_MESSAGE_ID] is answered with a delta and `turn_finished`. With [queuedAck],
 * `create_message` is acknowledged `queued`; otherwise the pre-ack path is used.
 */
internal class TurnEngineTestRecordingClient(
    private val syncReplay: AppServerInboundFrame.UpdateLoopStatus? = null,
    private val queuedAck: Boolean = false,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
    val aborts = mutableListOf<AppServerCommand.AbortMessage>()
    val syncs = mutableListOf<AppServerCommand.Sync>()
    val inputs = mutableListOf<AppServerCommand.Input>()
    val adminRpcs = mutableListOf<AppServerCommand.AdminRpc>()
    var onAbort: () -> Unit = {}

    /**
     * letta-mobile-qygvv.10: when set, `approval_response` inputs are acknowledged through this
     * (it may suspend, holding the ack) instead of taking the no-ack fallback.
     */
    var approvalAcks: (suspend (AppServerCommand.Input) -> AppServerInboundFrame.InputAccepted)? = null

    val approvalResponses: List<AppServerInputPayload.ApprovalResponse>
        get() = inputs.map { it.payload }.filterIsInstance<AppServerInputPayload.ApprovalResponse>()

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId)),
        )

    override suspend fun input(command: AppServerCommand.Input) {
        inputs += command
        val message = (command.payload as? AppServerInputPayload.CreateMessage)?.messages?.firstOrNull()
        if (message?.clientMessageId != AUTO_FINISH_MESSAGE_ID) return
        val run = TestRun("run-9")
        emit(run.assistantDelta())
        emit(run.turnFinished(9))
    }

    override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
        val approvalAck = approvalAcks?.takeIf { command.payload is AppServerInputPayload.ApprovalResponse }
        if (approvalAck != null) {
            inputs += command
            return approvalAck(command)
        }
        if (!queuedAck) throw UnsupportedOperationException("no ack")
        inputs += command
        return AppServerInboundFrame.InputAccepted(
            requestId = command.requestId.orEmpty(),
            runtime = command.runtime,
            accepted = true,
            disposition = "queued",
        )
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse {
        syncs += command
        syncReplay?.let { emit(it.copy(runtime = command.runtime, eventSeq = 50, idempotencyKey = "loop-replay-${syncs.size}")) }
        return AppServerInboundFrame.SyncResponse(requestId = command.requestId.orEmpty(), runtime = command.runtime, success = true)
    }

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
        onAbort()
        aborts += command
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = command.requestId.orEmpty(),
            runtime = command.runtime,
            aborted = true,
            success = true,
        )
    }

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
        adminRpcs += command
        return AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = false, error = "unexpected")
    }

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

    fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(frame.onStream())
    }
}
