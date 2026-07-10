package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolExecutionStatus
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Shared payload -> [ServerFrame] projection used by BOTH the initiator send
 * path ([IrohChannelTransport.emitDraft]) and the passive OBSERVER ingestion
 * loop ([IrohChannelTransport.ingestObserverFrame]).
 *
 * Extracting it guarantees the observer produces byte-identical frame shapes
 * to the initiator — the ONLY difference between the two paths is who supplies
 * the (agentId, conversationId, turnId, runId) context, and for
 * [RuntimeEventPayload.RemoteStreamFrame] even those are read from the wire
 * envelope first (context is only a fallback). This is the letta-mobile-r3i1z
 * "identical shape" contract: same mapper, same defaults, same output.
 *
 * Desktop's App Server controller gateway reuses this SAME object so its
 * turn/stream projections stay byte-identical to the Android iroh path
 * (cm-stream- ids, toolcall-/toolreturn- prefixes, the x1xnl stable assistant
 * otid) instead of re-deriving these hard-won conventions.
 */
object RuntimeEventServerFrameMapper {
    data class Context(
        val agentId: String,
        val conversationId: String,
        val turnId: String,
        val runId: String,
    )

    fun map(payload: RuntimeEventPayload, context: Context): List<ServerFrame> = when (payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> IrohStreamDeltaServerFrameMapper.map(
            payload = payload,
            context = IrohStreamDeltaServerFrameMapper.Context(
                agentId = context.agentId,
                conversationId = context.conversationId,
                turnId = context.turnId,
                runId = context.runId,
                timestamp = nowIso(),
            ),
        )
        // Non-chat App Server frames (update_device_status, update_queue,
        // update_subagent_state, etc.) are side-channel runtime events, not
        // assistant text. Do not fold them into the chat timeline.
        is RuntimeEventPayload.ExternalTransportFrame -> emptyList()
        is RuntimeEventPayload.ToolCallObserved -> listOf(toolCallFrame(payload, context))
        is RuntimeEventPayload.ToolReturnObserved -> listOf(toolReturnFrame(payload, context))
        is RuntimeEventPayload.ApprovalRequested -> listOf(approvalFrame(payload, context))
        is RuntimeEventPayload.RunLifecycleChanged -> lifecycleFrames(payload, context)
        else -> emptyList()
    }

    private fun toolCallFrame(
        payload: RuntimeEventPayload.ToolCallObserved,
        context: Context,
    ): ServerFrame.ToolCallMessage = toolCallMessage(
        context = context,
        id = "toolcall-${payload.toolCallId.value}",
        toolCallId = payload.toolCallId.value,
        name = payload.toolName.value,
        arguments = payload.argumentsJson ?: "{}",
    )

    private fun toolReturnFrame(
        payload: RuntimeEventPayload.ToolReturnObserved,
        context: Context,
    ): ServerFrame.ToolReturnMessage = ServerFrame.ToolReturnMessage(
        id = "toolreturn-${payload.toolCallId.value}",
        ts = nowIso(),
        agentId = context.agentId,
        conversationId = context.conversationId,
        turnId = context.turnId,
        runId = context.runId,
        toolCallId = payload.toolCallId.value,
        status = if (payload.status == ToolExecutionStatus.Failed) "error" else "success",
        toolReturn = JsonPrimitive(payload.body),
    )

    private fun approvalFrame(
        payload: RuntimeEventPayload.ApprovalRequested,
        context: Context,
    ): ServerFrame.ToolCallMessage = toolCallMessage(
        context = context,
        id = payload.request.approvalId.value,
        toolCallId = payload.request.callId.value,
        name = payload.request.toolName.value,
        arguments = payload.request.argumentsPreview ?: "{}",
        type = "approval_request_message",
    )

    /**
     * Shared [ServerFrame.ToolCallMessage] builder for [toolCallFrame] (an
     * observed tool call, default `tool_call_message` type) and
     * [approvalFrame] (an approval request re-shaped as a tool call, per the
     * §4.1 approval_request_message <-> tool_call_message contract above).
     * Both only differ in id/toolCallId/name/arguments/type.
     */
    private fun toolCallMessage(
        context: Context,
        id: String,
        toolCallId: String,
        name: String,
        arguments: String,
        type: String? = null,
    ): ServerFrame.ToolCallMessage {
        val message = ServerFrame.ToolCallMessage(
            id = id,
            ts = nowIso(),
            agentId = context.agentId,
            conversationId = context.conversationId,
            turnId = context.turnId,
            runId = context.runId,
            toolCall = ToolCallPayload(
                toolCallId = toolCallId,
                name = name,
                arguments = arguments,
            ),
            seq = null,
        )
        return if (type == null) message else message.copy(type = type)
    }

    private fun lifecycleFrames(
        payload: RuntimeEventPayload.RunLifecycleChanged,
        context: Context,
    ): List<ServerFrame> = when (payload.status) {
        RuntimeRunStatus.Completed -> listOf(turnDone(context, "completed"))
        RuntimeRunStatus.Failed -> {
            com.letta.mobile.util.Telemetry.event(
                "IrohTransport", "turn.lifecycle_failed", "reason" to (payload.reason ?: ""),
            )
            listOf(turnDone(context, "failed"))
        }
        RuntimeRunStatus.Cancelled -> listOf(turnDone(context, "cancelled"))
        RuntimeRunStatus.Started, RuntimeRunStatus.Running -> emptyList()
    }

    private fun turnDone(context: Context, status: String): ServerFrame.TurnDone =
        ServerFrame.TurnDone(
            id = "turn_done-${UUID.randomUUID()}",
            ts = nowIso(),
            turnId = context.turnId,
            runId = context.runId,
            status = status,
        )

    private fun nowIso(): String = Instant.now().toString()
}
