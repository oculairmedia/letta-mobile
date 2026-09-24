package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeUserInputTools
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What [UnleasedApprovalAnswerer] did with one control request. */
internal enum class UnleasedApprovalOutcome {
    /** A lease exists for the key (or the frame is runtime-less): the turn answers it. */
    Deferred,

    /** Not a `can_use_tool` permission request; nothing to decide here. */
    NotApproval,

    /** Auto-allowed under the key's Unrestricted policy. */
    AutoAllowed,

    /** An interactive user-input tool, or a non-Unrestricted policy: stays pending for a viewer. */
    LeftPending,

    /** Another observer already claimed or answered it. */
    AlreadyClaimed,
}

/**
 * letta-mobile-qygvv.3: the approval twin of `answerUnleasedExternalToolCall`.
 *
 * A `control_request` (can_use_tool) that arrives while NO turn holds a lease for its runtime key
 * (the turn's collector ended, a reconnect replayed it, or the server raced the end of a turn)
 * used to sit in the fanout's pending buffer until the next turn subscribed. Meanwhile the server
 * turn was parked on it and the conversation queue behind it wedged.
 *
 * It now gets the policy the lease would have applied: under Unrestricted a non-interactive tool
 * is allowed at once. An interactive user-input tool (AskUserQuestion) is never auto-allowed; it
 * stays pending so the next viewer surfaces it. Claims go through [InboundControlRequestRegistry],
 * so a turn that subscribes later drops the already-answered request instead of answering twice.
 */
internal class UnleasedApprovalAnswerer(
    private val client: AppServerClient,
    private val inboundControlRegistry: InboundControlRequestRegistry,
    private val connectionGenerationProvider: () -> Long,
    private val leaseHeld: (TurnRuntimeKey) -> Boolean,
    private val permissionModeFor: (TurnRuntimeKey) -> AppServerPermissionMode,
    private val runtimeScopeFor: (TurnRuntimeKey) -> AppServerRuntimeScope?,
) {
    suspend fun answer(
        request: AppServerInboundFrame.ControlRequest,
        connectionGeneration: Long? = null,
    ): UnleasedApprovalOutcome {
        val runtime = request.runtime ?: return UnleasedApprovalOutcome.Deferred
        val key = TurnRuntimeKey(runtime.agentId, runtime.conversationId)
        if (leaseHeld(key)) return UnleasedApprovalOutcome.Deferred
        if (request.request.string("subtype") != CAN_USE_TOOL) return UnleasedApprovalOutcome.NotApproval
        val toolName = request.request.string("tool_name")
        val mode = permissionModeFor(key)
        if (mode != AppServerPermissionMode.Unrestricted || RuntimeUserInputTools.requiresUserInput(toolName)) {
            record("approval.unleasedPending", request, key, toolName, mode)
            return UnleasedApprovalOutcome.LeftPending
        }
        val generation = connectionGeneration ?: connectionGenerationProvider()
        if (!claim(request, key, generation)) return UnleasedApprovalOutcome.AlreadyClaimed
        val ref = InboundControlRequestRegistry.RequestRef(request.requestId)
        try {
            client.input(
                AppServerCommand.Input(
                    runtime = runtimeScopeFor(key) ?: runtime,
                    payload = AppServerInputPayload.ApprovalResponse(
                        requestId = request.requestId,
                        decision = AppServerApprovalResponseDecision.Allow(
                            message = "Approved by default mobile policy.",
                        ),
                    ),
                ),
            )
        } catch (error: Throwable) {
            // Hand the request back so a replay or a later turn can still answer it.
            inboundControlRegistry.releaseClaim(ref, AppServerTurnEngine.UNLEASED_LEASE_TOKEN, generation)
            throw error
        }
        inboundControlRegistry.markAnswered(ref, generation)
        record("approval.unleasedAutoAllow", request, key, toolName, mode)
        return UnleasedApprovalOutcome.AutoAllowed
    }

    private fun claim(
        request: AppServerInboundFrame.ControlRequest,
        key: TurnRuntimeKey,
        generation: Long,
    ): Boolean {
        inboundControlRegistry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = request.requestId,
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = generation,
                agentId = key.agentId,
                conversationId = key.conversationId,
            ),
        )
        return inboundControlRegistry.tryClaim(
            InboundControlRequestRegistry.RequestRef(request.requestId),
            AppServerTurnEngine.UNLEASED_LEASE_TOKEN,
            generation,
        )
    }

    private fun record(
        event: String,
        request: AppServerInboundFrame.ControlRequest,
        key: TurnRuntimeKey,
        toolName: String?,
        mode: AppServerPermissionMode,
    ) {
        Telemetry.event(
            "AppServerTurnEngine", event,
            "requestId" to request.requestId,
            "key" to key.toString(),
            "tool" to (toolName ?: ""),
            "permissionMode" to mode.name,
            level = Telemetry.Level.WARN,
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val CAN_USE_TOOL = "can_use_tool"
    }
}

/**
 * A stand-in command for a runtime key with no turn, so the host's per-command permission-mode
 * provider (which only reads the agent and conversation) can answer for that key.
 */
internal fun unleasedCommandFor(key: TurnRuntimeKey): TurnCommand = TurnCommand(
    backendId = BackendId("app-server-unleased"),
    runtimeId = RuntimeId("unleased:$key"),
    agentId = AgentId(key.agentId),
    conversationId = ConversationId(key.conversationId),
    input = TurnInput.UserMessage(localMessageId = "unleased-control-request", text = ""),
)
