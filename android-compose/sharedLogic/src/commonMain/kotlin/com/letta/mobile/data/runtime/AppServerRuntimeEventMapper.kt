package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-gdvbf: `open` so the injected-mapper seam that
 * [AppServerTurnEngine] already exposes is actually usable — the engine takes
 * a mapper as a constructor parameter for testing, but the type being final
 * made that impossible to exercise.
 */
open class AppServerRuntimeEventMapper {
    open fun map(command: TurnCommand, received: AppServerReceivedFrame): List<RuntimeEventDraft> =
        when (val frame = received.frame) {
            is AppServerInboundFrame.AuthResponse -> emptyList()
            is AppServerInboundFrame.RuntimeStartResponse -> emptyList()
            is AppServerInboundFrame.SyncResponse -> emptyList()
            // Native admin request/response frames (lgns8.7) are correlated by
            // the request registry; they are not runtime turn events.
            is AppServerInboundFrame.AgentListResponse,
            is AppServerInboundFrame.AgentRetrieveResponse,
            is AppServerInboundFrame.AgentCreateResponse,
            is AppServerInboundFrame.AgentUpdateResponse,
            is AppServerInboundFrame.AgentDeleteResponse,
            is AppServerInboundFrame.ConversationListResponse,
            is AppServerInboundFrame.ConversationRetrieveResponse,
            is AppServerInboundFrame.ConversationCreateResponse,
            is AppServerInboundFrame.ConversationUpdateResponse,
            is AppServerInboundFrame.ConversationMessagesListResponse,
            is AppServerInboundFrame.ConversationCompactResponse,
            is AppServerInboundFrame.ListModelsResponse,
            is AppServerInboundFrame.SkillEnableResponse,
            is AppServerInboundFrame.SkillDisableResponse,
            is AppServerInboundFrame.SkillsUpdated,
            is AppServerInboundFrame.CronListResponse,
            is AppServerInboundFrame.CronAddResponse,
            is AppServerInboundFrame.CronGetResponse,
            is AppServerInboundFrame.CronRunsResponse,
            is AppServerInboundFrame.CronTriggerResponse,
            is AppServerInboundFrame.CronUpdateResponse,
            is AppServerInboundFrame.CronDeleteResponse,
            is AppServerInboundFrame.CronDeleteAllResponse,
            is AppServerInboundFrame.WriteMemoryFileResponse,
            is AppServerInboundFrame.GetReflectionSettingsResponse,
            is AppServerInboundFrame.SetReflectionSettingsResponse,
            is AppServerInboundFrame.GetCwdMapResponse,
            // Channels host (lgns8.23): controller-initiated, correlated by the
            // request registry, and credential-bearing — never a turn event and
            // never mapped into a runtime draft that could reach a viewer.
            is AppServerInboundFrame.ChannelsListResponse,
            is AppServerInboundFrame.ChannelAccountsListResponse,
            is AppServerInboundFrame.ChannelStartResponse,
            is AppServerInboundFrame.ChannelAccountUpdateResponse,
            // Capability discovery (lgns8.24): correlated by the request registry,
            // not a runtime turn event.
            is AppServerInboundFrame.AppServerInfoResponse,
            -> emptyList()
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
            // Malformed/undecodable frames are surfaced as external transport drafts
            // (same as Unknown) so they are observable without killing the loop.
            is AppServerInboundFrame.DecodeFailure -> listOf(received.toExternalTransportDraft(command))
        }

    private fun AppServerInboundFrame.AbortMessageResponse.toAbortDraft(command: TurnCommand): List<RuntimeEventDraft> =
        if (success && aborted) {
            listOf(command.lifecycle(RuntimeRunStatus.Cancelled, reason = null))
        } else if (!success) {
            listOf(command.lifecycle(RuntimeRunStatus.Failed, reason = error ?: "App Server abort failed"))
        } else {
            emptyList()
        }

    /**
     * letta-mobile-gdvbf: a `stream_delta` whose `delta` is not a JSON object.
     *
     * Because this shape is now HANDLED rather than thrown, it would otherwise
     * be invisible — no exception means no `frame.projection_failed` event, so
     * the exact malformed shape that motivated this work would stop
     * self-identifying. It gets its own classification event instead, and the
     * frame is still surfaced as an external-transport draft (the treatment
     * Unknown and DecodeFailure frames get) so it stays observable in the
     * timeline without deciding the terminal.
     */
    private fun AppServerInboundFrame.StreamDelta.unprojectableDelta(
        command: TurnCommand,
        raw: JsonObject,
    ): List<RuntimeEventDraft> {
        Telemetry.event(
            "AppServerRuntimeEventMapper", "frame.unprojectable_delta",
            "reason" to "delta_not_an_object",
            "deltaKind" to (delta::class.simpleName ?: "JsonElement"),
            "conversationId" to runtime.conversationId,
            "agentId" to runtime.agentId,
            "eventSeq" to eventSeq,
            "idempotencyKey" to idempotencyKey,
        )
        return listOf(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = this,
                raw = raw,
            ).toExternalTransportDraft(command),
        )
    }

    private fun AppServerInboundFrame.StreamDelta.toStreamDeltaDraft(
        command: TurnCommand,
        raw: JsonObject,
    ): List<RuntimeEventDraft> {
        // letta-mobile-gdvbf: `.jsonObject` THROWS on a non-object delta, and
        // that throw used to escape into the turn collect loop and settle the
        // whole turn as a stream error. A delta we cannot read is one frame we
        // cannot project — surface it as an external-transport draft (the same
        // treatment Unknown and DecodeFailure frames get) so it stays
        // observable without deciding the terminal.
        val deltaObject = delta as? JsonObject
            ?: return unprojectableDelta(command, raw)
        val messageType = deltaObject.string("message_type")
        val runId = deltaObject.string("run_id")?.let(::RunId)
        return when (messageType) {
            "stop_reason" -> {
                val stopDraft = command.draft(
                    runId = runId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RemoteStreamFrame(
                        frameId = idempotencyKey,
                        messageId = deltaObject.string("id"),
                        messageType = messageType,
                        body = raw.toString(),
                    ),
                )
                if (deltaObject.isTerminalStopReason()) {
                    val stopReason = deltaObject.string("stop_reason") ?: deltaObject.string("reason")
                    val lifecycleDraft = when (stopReason) {
                        "cancelled" -> command.lifecycle(RuntimeRunStatus.Cancelled, runId = runId)
                        "error" -> command.lifecycle(
                            RuntimeRunStatus.Failed,
                            runId = runId,
                            reason = deltaObject.errorMessage("App Server turn stopped with error"),
                        )
                        else -> command.lifecycle(RuntimeRunStatus.Completed, runId = runId)
                    }
                    listOf(stopDraft, lifecycleDraft)
                } else {
                    listOf(stopDraft)
                }
            }
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

    /**
     * letta-mobile-fkpd4: FAIL-SOFT wire read — see the sibling accessor in
     * [com.letta.mobile.data.subagents.SubagentParentProjection]. `.jsonPrimitive`
     * THROWS on a JsonArray/JsonObject, and this mapper runs inside
     * [AppServerTurnEngine]'s turn collect loop over RAW App Server deltas,
     * where `content`, `status`, `output` and `message` can all legitimately
     * arrive non-scalar. A throw here does not degrade one field — it kills the
     * whole turn and settles it as "Tool execution interrupted by stream
     * error". Never throw on a wire read.
     */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.isTerminalStopReason(): Boolean {
        val reason = string("stop_reason") ?: string("reason") ?: return true
        // `length` is the OpenAI-compat finish_reason for output/context caps;
        // providers on the lmstudio path (e.g. MiniMax-M3) emit it instead of
        // `max_tokens`. Treat it as terminal Completed the same way — otherwise
        // the turn stays open until a later error_message paints a false Failed.
        return reason == "end_turn" || reason == "stop_sequence" || reason == "max_tokens" ||
            reason == "length" || reason == "cancelled" || reason == "error"
    }

    private fun JsonObject.errorMessage(fallback: String = "App Server turn failed"): String =
        string("message")
            ?: objectOrNull("api_error")?.string("message")
            ?: objectOrNull("api_error")?.string("detail")
            ?: fallback
}
