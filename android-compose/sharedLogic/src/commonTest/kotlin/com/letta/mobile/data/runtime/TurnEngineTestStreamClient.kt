package com.letta.mobile.data.runtime

import app.cash.turbine.ReceiveTurbine
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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
