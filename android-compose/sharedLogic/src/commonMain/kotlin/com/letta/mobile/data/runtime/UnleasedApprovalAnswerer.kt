package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.ApprovalSubmission
import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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

    /**
     * letta-mobile-qygvv.10: this client already sent a decision for the request (a retry, a
     * second observer, or a server replay). No second frame: a replay is re-answered from the
     * [com.letta.mobile.data.controller.fanout.ApprovalDecisionCache] by the router's responder.
     */
    AlreadyDecided,

    /**
     * letta-mobile-qygvv.10: the App Server's `input_accepted` rejected the auto-allow (e.g.
     * "Approval request is no longer pending"). Recorded as `approval.rejected`, exactly like a
     * leased send, and the claim is handed back.
     */
    Rejected,

    /**
     * No turn on this engine ever ran on the runtime key: another client owns it, and its own
     * permission policy decides. Left pending, never auto-allowed.
     */
    NotOwned,
}

/**
 * letta-mobile-qygvv.3: the approval twin of `answerUnleasedExternalToolCall`.
 *
 * A `control_request` (can_use_tool) that arrives while NO turn holds a lease for its runtime key
 * (the turn's collector ended, a reconnect replayed it, or the server raced the end of a turn)
 * used to sit in the fanout's pending buffer until the next turn subscribed. Meanwhile the server
 * turn was parked on it and the conversation queue behind it wedged.
 *
 * It now gets the policy the lease would have applied, but only for a runtime key this engine
 * has run a turn on: under Unrestricted a non-interactive tool
 * is allowed at once. An interactive user-input tool (AskUserQuestion) is never auto-allowed; it
 * stays pending so the next viewer surfaces it. Claims go through [InboundControlRequestRegistry],
 * so a turn that subscribes later drops the already-answered request instead of answering twice.
 */
internal class UnleasedApprovalAnswerer(
    /** letta-mobile-qygvv.10: the same sender leased approvals use (decision cache + `input_accepted`). */
    private val sender: ApprovalResponseSender,
    private val inboundControlRegistry: InboundControlRequestRegistry,
    private val connectionGenerationProvider: () -> Long,
    private val leaseHeld: (TurnRuntimeKey) -> Boolean,
    /** Whether this engine ran a turn on the key, i.e. the key's permission mode is ours to apply. */
    private val runtimeOwned: (TurnRuntimeKey) -> Boolean,
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
        if (sender.cachedDecisionFor(request) != null) return UnleasedApprovalOutcome.AlreadyDecided
        val details = ApprovalDetails(request, key, toolName, permissionModeFor(key))
        return withheldOutcome(details) ?: autoAllow(details, runtime, connectionGeneration)
    }

    /** Why [details] must not be auto-allowed here, or null when the key's policy allows it. */
    private fun withheldOutcome(details: ApprovalDetails): UnleasedApprovalOutcome? {
        // Review of PR #1661: `client.events` carries every runtime on a shared App Server. For a
        // key this engine never ran, the mode provider only returns the host DEFAULT (approve-all),
        // which is not the owning client's policy, so auto-allowing would bypass that policy.
        if (!runtimeOwned(details.key)) {
            record("approval.unleasedNotOwned", details)
            return UnleasedApprovalOutcome.NotOwned
        }
        if (shouldStayPending(details.mode, details.toolName)) {
            record("approval.unleasedPending", details)
            return UnleasedApprovalOutcome.LeftPending
        }
        return null
    }

    private suspend fun autoAllow(
        details: ApprovalDetails,
        runtime: AppServerRuntimeScope,
        connectionGeneration: Long?,
    ): UnleasedApprovalOutcome {
        val generation = connectionGeneration ?: connectionGenerationProvider()
        if (!claim(details.request, details.key, generation)) return UnleasedApprovalOutcome.AlreadyClaimed
        val ref = InboundControlRequestRegistry.RequestRef(details.request.requestId)
        val targetRuntime = runtimeScopeFor(details.key) ?: runtime
        val result = sendAutoAllow(details.request.requestId, targetRuntime, ref, generation)
        if (result is ApprovalSubmitResult.Rejected) {
            record("approval.unleasedRejected", details)
            return UnleasedApprovalOutcome.Rejected
        }
        record("approval.unleasedAutoAllow", details)
        return UnleasedApprovalOutcome.AutoAllowed
    }

    private fun shouldStayPending(mode: AppServerPermissionMode, toolName: String?): Boolean {
        if (mode != AppServerPermissionMode.Unrestricted) return true
        return RuntimeUserInputTools.requiresUserInput(toolName)
    }

    /**
     * letta-mobile-qygvv.10: sends through [sender] like a leased auto-approve: the decision is
     * cached before the send and `input_accepted` is awaited. Marked answered unless rejected
     * (the same rule as `AppServerTurnEngine.submitApprovalResponse`); a rejection or a send
     * failure hands the claim back so a later turn or replay can still answer it.
     */
    private suspend fun sendAutoAllow(
        requestId: String,
        targetRuntime: AppServerRuntimeScope,
        ref: InboundControlRequestRegistry.RequestRef,
        generation: Long,
    ): ApprovalSubmitResult {
        val result = try {
            sender.send(autoAllowSubmission(targetRuntime, requestId))
        } catch (error: Throwable) {
            releaseClaim(ref, generation)
            throw error
        }
        if (result is ApprovalSubmitResult.Rejected) {
            releaseClaim(ref, generation)
        } else {
            inboundControlRegistry.markAnswered(ref, generation)
        }
        return result
    }

    private fun releaseClaim(ref: InboundControlRequestRegistry.RequestRef, generation: Long) {
        inboundControlRegistry.releaseClaim(ref, AppServerTurnEngine.UNLEASED_LEASE_TOKEN, generation)
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
        details: ApprovalDetails,
    ) {
        Telemetry.event(
            "AppServerTurnEngine", event,
            "requestId" to details.request.requestId,
            "key" to details.key.toString(),
            "tool" to (details.toolName ?: ""),
            "permissionMode" to details.mode.name,
            level = Telemetry.Level.WARN,
        )
    }

    private data class ApprovalDetails(
        val request: AppServerInboundFrame.ControlRequest,
        val key: TurnRuntimeKey,
        val toolName: String?,
        val mode: AppServerPermissionMode,
    )

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val CAN_USE_TOOL = "can_use_tool"
    }
}

/** The unleased auto-allow, as the one approval sender takes it. */
private fun autoAllowSubmission(runtime: AppServerRuntimeScope, requestId: String) = ApprovalSubmission(
    runtime = runtime,
    approvalRequestId = requestId,
    decision = AppServerApprovalResponseDecision.Allow(message = "Approved by default mobile policy."),
    source = "unleased_auto_allow",
)

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
