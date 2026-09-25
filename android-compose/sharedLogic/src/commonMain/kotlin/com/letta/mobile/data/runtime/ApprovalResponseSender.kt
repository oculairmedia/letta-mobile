package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.ApprovalSubmission
import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.ApprovalDecisionCache
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * letta-mobile-qygvv.5: the one send path for `approval_response` inputs (user
 * submit, auto-approve, `TurnInput.ToolApprovalResponse`, cached replay re-answer).
 *
 * Every response carries a `request_id` and waits for `input_accepted`, so a
 * decision that missed its gate comes back as [ApprovalSubmitResult.Rejected]
 * instead of leaving the turn parked until the idle watchdog. The decision is
 * cached BEFORE the send, so a replay that races the ack is answered with the same
 * decision, and forgotten again when the server rejects it.
 */
internal class ApprovalResponseSender(
    private val client: AppServerClient,
    private val decisions: ApprovalDecisionCache,
    private val requestIdFactory: () -> String,
) {
    private val inFlight = InFlightApprovalSends()

    suspend fun send(submission: ApprovalSubmission): ApprovalSubmitResult {
        val cached = ApprovalDecisionCache.CachedDecision(
            submission.runtime,
            submission.approvalRequestId,
            submission.decision,
        )
        decisions.remember(cached)
        val result = deliver(cached)
        if (submission.isAlreadyResolvedBy(result)) {
            recordAlreadyResolved(submission)
            return ApprovalSubmitResult.Accepted
        }
        record(cached, result, submission.source)
        return result
    }

    /**
     * letta-mobile-qygvv.13: under Unrestricted the App Server approves the tool itself and
     * still streams an `approval_request_message`; a reply to that delta is acked with
     * "Approval request is no longer pending". That is the server having resolved it, so it
     * is handled exactly like an accepted reply. A real `control_request` keeps the rejection.
     */
    private fun ApprovalSubmission.isAlreadyResolvedBy(result: ApprovalSubmitResult): Boolean =
        answersStreamDelta &&
            result is ApprovalSubmitResult.Rejected &&
            result.error == APPROVAL_NO_LONGER_PENDING

    private fun recordAlreadyResolved(submission: ApprovalSubmission) {
        Telemetry.event(
            TELEMETRY_TAG, "approval.already_resolved",
            "approvalId" to submission.approvalRequestId,
            "conversationId" to submission.runtime.conversationId,
            "toolName" to (submission.toolName ?: ""),
            "source" to submission.source,
            level = Telemetry.Level.INFO,
        )
    }

    fun cachedDecisionFor(frame: AppServerInboundFrame.ControlRequest): ApprovalDecisionCache.CachedDecision? =
        decisions.lookup(frame)

    /** Re-sends a cached decision for a server replay of the same request. */
    suspend fun reanswer(cached: ApprovalDecisionCache.CachedDecision): ApprovalSubmitResult {
        if (inFlight.contains(cached.key)) return coalescedWithInFlight(cached)
        val result = deliver(cached)
        Telemetry.event(
            TELEMETRY_TAG, "approval.replay_reanswered",
            "approvalId" to cached.requestId,
            "conversationId" to cached.runtime.conversationId,
            "outcome" to result.outcome(),
        )
        record(cached, result, source = "replay")
        return result
    }

    /** letta-mobile-qygvv.10: the same decision is awaiting its ack; its own result settles it. */
    private fun coalescedWithInFlight(cached: ApprovalDecisionCache.CachedDecision): ApprovalSubmitResult {
        Telemetry.event(
            TELEMETRY_TAG, "approval.replay_in_flight",
            "approvalId" to cached.requestId,
            "conversationId" to cached.runtime.conversationId,
        )
        return ApprovalSubmitResult.Unacknowledged(IN_FLIGHT_REASON)
    }

    private suspend fun deliver(cached: ApprovalDecisionCache.CachedDecision): ApprovalSubmitResult =
        inFlight.track(cached.key) { deliverNow(cached) }

    private suspend fun deliverNow(cached: ApprovalDecisionCache.CachedDecision): ApprovalSubmitResult {
        val command = AppServerCommand.Input(
            runtime = cached.runtime,
            payload = AppServerInputPayload.ApprovalResponse(
                requestId = cached.requestId,
                decision = cached.decision,
            ),
        )
        return when (val acceptance = client.sendInputAwaitingAcceptance(command, requestIdFactory())) {
            InputAcceptance.Started, InputAcceptance.Queued -> ApprovalSubmitResult.Accepted
            is InputAcceptance.Rejected -> ApprovalSubmitResult.Rejected(acceptance.error)
            is InputAcceptance.ConnectionLost -> ApprovalSubmitResult.Unacknowledged("connection_lost")
            is InputAcceptance.Unacknowledged -> ApprovalSubmitResult.Unacknowledged(acceptance.reason)
        }
    }

    private fun record(
        cached: ApprovalDecisionCache.CachedDecision,
        result: ApprovalSubmitResult,
        source: String,
    ) {
        when (result) {
            ApprovalSubmitResult.Accepted -> Telemetry.event(
                TELEMETRY_TAG, "approval.accepted",
                "approvalId" to cached.requestId,
                "conversationId" to cached.runtime.conversationId,
                "source" to source,
            )
            is ApprovalSubmitResult.Rejected -> {
                decisions.forget(cached)
                Telemetry.event(
                    TELEMETRY_TAG, "approval.rejected",
                    "approvalId" to cached.requestId,
                    "conversationId" to cached.runtime.conversationId,
                    "source" to source,
                    "error" to result.error,
                    level = Telemetry.Level.WARN,
                )
            }
            is ApprovalSubmitResult.Unacknowledged -> Telemetry.event(
                TELEMETRY_TAG, "approval.unacknowledged",
                "approvalId" to cached.requestId,
                "conversationId" to cached.runtime.conversationId,
                "source" to source,
                "reason" to result.reason,
            )
        }
    }

    private fun ApprovalSubmitResult.outcome(): String = when (this) {
        ApprovalSubmitResult.Accepted -> "accepted"
        is ApprovalSubmitResult.Rejected -> "rejected"
        is ApprovalSubmitResult.Unacknowledged -> "unacknowledged"
    }

    internal companion object {
        const val TELEMETRY_TAG = "ApprovalResponse"

        /** The App Server's `input_accepted` error for an approval with no pending gate. */
        const val APPROVAL_NO_LONGER_PENDING = "Approval request is no longer pending"

        /** A replay of a decision whose send is still awaiting `input_accepted`. */
        const val IN_FLIGHT_REASON = "in_flight"
    }
}

/**
 * letta-mobile-qygvv.5: when [frame] is a `control_request` this client already
 * answered (a server replay: the answer was lost), re-sends the cached decision on
 * [launchScope] and returns true so the caller drops the frame instead of
 * surfacing a second approval card. A replay scoped to another runtime is left
 * alone.
 */
internal fun ApprovalResponseSender.reanswerCachedReplay(
    frame: AppServerInboundFrame,
    runtime: AppServerRuntimeScope,
    launchScope: CoroutineScope,
): Boolean {
    val control = frame as? AppServerInboundFrame.ControlRequest ?: return false
    val scope = control.runtime
    if (scope != null) {
        if (scope.agentId != runtime.agentId) return false
        if (scope.conversationId != runtime.conversationId) return false
    }
    val cached = cachedDecisionFor(control) ?: return false
    launchScope.launch { reanswer(cached) }
    return true
}

/** letta-mobile-qygvv.5: the decision already sent for a replayed [frame], if this engine answered it. */
fun AppServerTurnEngine.cachedApprovalDecisionFor(
    frame: AppServerInboundFrame.ControlRequest,
): ApprovalDecisionCache.CachedDecision? = approvalSender.cachedDecisionFor(frame)

/**
 * letta-mobile-qygvv.5: the captured user-input gate id for [toolCallId] is released once its
 * answer went through; a rejected answer leaves the gate open for a retry.
 */
fun AppServerTurnEngine.releaseUserInputGateUnlessRejected(
    result: ApprovalSubmitResult,
    toolCallId: String?,
    capturedRequestId: String?,
) {
    if (result is ApprovalSubmitResult.Rejected) return
    if (toolCallId == null || capturedRequestId == null) return
    clearUserInputApprovalId(toolCallId, capturedRequestId)
}

/** letta-mobile-qygvv.5: re-sends [cached] for a server replay of the same request (awaits `input_accepted`). */
suspend fun AppServerTurnEngine.reanswerReplayedApproval(
    cached: ApprovalDecisionCache.CachedDecision,
): ApprovalSubmitResult = approvalSender.reanswer(cached)

/**
 * letta-mobile-qygvv.5: hosts that build an engine on their own [router] (desktop,
 * Iroh dialer) call this so a replayed `control_request` for a request this engine
 * already answered is re-answered on [scope] instead of being dropped by the fanout.
 */
fun AppServerTurnEngine.answerApprovalReplaysFrom(router: AppServerRuntimeEventRouter, scope: CoroutineScope) {
    router.bindApprovalReplayResponder { frame ->
        val cached = cachedApprovalDecisionFor(frame)
        if (cached != null) scope.launch { reanswerReplayedApproval(cached) }
        cached != null
    }
}

/** letta-mobile-qygvv.5: the approval decision [this] input carries, when it is an `approval_response`. */
internal fun AppServerCommand.Input.approvalResponseOrNull(): AppServerInputPayload.ApprovalResponse? =
    (payload as? AppServerInputPayload.ApprovalResponse)?.takeIf { it.decision != null }

/** Sends a `TurnInput.ToolApprovalResponse`: only a rejected decision fails the turn. */
internal suspend fun ApprovalResponseSender.sendAsTurnInput(
    runtime: AppServerRuntimeScope,
    response: AppServerInputPayload.ApprovalResponse,
): InputAcceptance.Failure? {
    val decision = response.decision ?: return null
    val result = send(ApprovalSubmission(runtime, response.requestId, decision, source = "turn_input"))
    return (result as? ApprovalSubmitResult.Rejected)?.let { InputAcceptance.Rejected(it.error) }
}
