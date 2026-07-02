package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppServerRuntimeEventMapper {
    fun map(command: TurnCommand, received: AppServerReceivedFrame): List<RuntimeEventDraft> =
        when (val frame = received.frame) {
            is AppServerInboundFrame.AuthResponse -> emptyList()
            is AppServerInboundFrame.RuntimeStartResponse -> emptyList()
            is AppServerInboundFrame.SyncResponse -> emptyList()
            is AppServerInboundFrame.AbortMessageResponse -> frame.toAbortDraft(command)
            is AppServerInboundFrame.StreamDelta -> frame.toStreamDeltaDraft(command, received.raw)
            is AppServerInboundFrame.UpdateLoopStatus -> frame.toLoopStatusDraft(command)
            is AppServerInboundFrame.UpdateDeviceStatus,
            is AppServerInboundFrame.UpdateQueue,
            is AppServerInboundFrame.UpdateSubagentState,
            -> listOf(received.toExternalTransportDraft(command))
            is AppServerInboundFrame.ExternalToolCallRequest -> frame.toToolCallDraft(command)
            is AppServerInboundFrame.ControlRequest -> frame.toApprovalOrExternalDraft(command, received)
            is AppServerInboundFrame.AdminRpcResponse -> emptyList() // handled by IrohAdminRpcClient
            is AppServerInboundFrame.Unknown -> listOf(received.toExternalTransportDraft(command))
        }

    private fun AppServerInboundFrame.AbortMessageResponse.toAbortDraft(command: TurnCommand): List<RuntimeEventDraft> =
        if (success && aborted) {
            listOf(command.lifecycle(RuntimeRunStatus.Cancelled, reason = null))
        } else if (!success) {
            listOf(command.lifecycle(RuntimeRunStatus.Failed, reason = error ?: "App Server abort failed"))
        } else {
            emptyList()
        }

    private fun AppServerInboundFrame.StreamDelta.toStreamDeltaDraft(
        command: TurnCommand,
        raw: JsonObject,
    ): List<RuntimeEventDraft> {
        val deltaObject = delta.jsonObject
        val messageType = deltaObject.string("message_type")
        val runId = deltaObject.string("run_id")?.let(::RunId)
        return when (messageType) {
            "stop_reason" -> listOf(command.lifecycle(RuntimeRunStatus.Completed, runId = runId))
            "loop_error",
            "error_message",
            -> listOf(command.lifecycle(RuntimeRunStatus.Failed, runId = runId, reason = deltaObject.errorMessage()))
            "client_tool_start" -> listOf(
                command.draft(
                    runId = runId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.ToolCallObserved(
                        toolCallId = ToolCallId(deltaObject.string("tool_call_id") ?: idempotencyKey),
                        toolName = ToolName(deltaObject.string("tool_name") ?: deltaObject.string("name") ?: "client_tool"),
                        argumentsJson = deltaObject["input"]?.toString() ?: deltaObject["arguments"]?.toString(),
                    ),
                ),
            )
            "client_tool_end" -> listOf(
                command.draft(
                    runId = runId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.ToolReturnObserved(
                        toolCallId = ToolCallId(deltaObject.string("tool_call_id") ?: idempotencyKey),
                        status = if (deltaObject.string("status") == "error") {
                            ToolExecutionStatus.Failed
                        } else {
                            ToolExecutionStatus.Succeeded
                        },
                        body = deltaObject.string("output") ?: deltaObject.string("message") ?: delta.toString(),
                    ),
                ),
            )
            else -> listOf(
                command.draft(
                    runId = runId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RemoteStreamFrame(
                        frameId = idempotencyKey,
                        messageId = deltaObject.string("id"),
                        messageType = messageType,
                        body = raw.toString(),
                    ),
                ),
            )
        }
    }

    private fun AppServerInboundFrame.UpdateLoopStatus.toLoopStatusDraft(command: TurnCommand): List<RuntimeEventDraft> =
        if (loopStatus.activeRunIds.isNotEmpty()) {
            listOf(command.lifecycle(RuntimeRunStatus.Running, runId = RunId(loopStatus.activeRunIds.first())))
        } else {
            emptyList()
        }

    private fun AppServerInboundFrame.ExternalToolCallRequest.toToolCallDraft(command: TurnCommand): List<RuntimeEventDraft> =
        listOf(
            command.draft(
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.ToolCallObserved(
                    toolCallId = ToolCallId(toolCallId),
                    toolName = ToolName(toolName),
                    argumentsJson = input.toString(),
                ),
            ),
        )

    private fun AppServerInboundFrame.ControlRequest.toApprovalOrExternalDraft(
        command: TurnCommand,
        received: AppServerReceivedFrame,
    ): List<RuntimeEventDraft> {
        if (request.string("subtype") != "can_use_tool") {
            return listOf(received.toExternalTransportDraft(command))
        }
        val toolCallId = request.string("tool_call_id") ?: requestId
        val toolName = request.string("tool_name") ?: "tool"
        return listOf(
            command.draft(
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.ApprovalRequested(
                    ToolApprovalRequest(
                        approvalId = ToolApprovalId(requestId),
                        callId = ToolCallId(toolCallId),
                        toolName = ToolName(toolName),
                        prompt = "Allow $toolName?",
                        argumentsPreview = request["input"]?.toString(),
                    ),
                ),
            ),
        )
    }

    private fun AppServerReceivedFrame.toExternalTransportDraft(command: TurnCommand): RuntimeEventDraft =
        command.draft(
            source = RuntimeEventSource.ExternalTransport,
            payload = RuntimeEventPayload.ExternalTransportFrame(
                frameId = raw.string("idempotency_key") ?: frame.requestId ?: frame.type ?: "app-server-frame",
                transportMessageId = frame.requestId,
                body = raw.toString(),
            ),
        )

    private fun TurnCommand.lifecycle(
        status: RuntimeRunStatus,
        runId: RunId? = null,
        reason: String? = null,
    ): RuntimeEventDraft =
        draft(
            runId = runId,
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RunLifecycleChanged(
                status = status,
                reason = reason,
            ),
        )

    private fun TurnCommand.draft(
        runId: RunId? = null,
        source: RuntimeEventSource,
        payload: RuntimeEventPayload,
    ): RuntimeEventDraft =
        RuntimeEventDraft(
            backendId = backendId,
            runtimeId = runtimeId,
            agentId = agentId,
            conversationId = conversationId,
            runId = runId,
            source = source,
            payload = payload,
        )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.errorMessage(): String =
        string("message")
            ?: this["api_error"]?.jsonObject?.string("message")
            ?: this["api_error"]?.jsonObject?.string("detail")
            ?: "App Server turn failed"
}
