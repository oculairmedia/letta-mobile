package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.approval.ApprovalSubmissionTracker
import com.letta.mobile.data.chat.runtime.ApprovalSubmittingGateway
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ApprovalSubmissionRequest(
    val gateway: DesktopChatGateway?,
    val conversation: DesktopConversationSummary?,
    val requestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String?,
)

/**
 * Tracks, submits, and reconciles user approvals with the backend.
 */
internal class DesktopChatApprovalCoordinator(
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    /** The shared in-flight tracker (letta-mobile-2dsi5.4): the same one Android's approval controller uses. */
    private val tracker = ApprovalSubmissionTracker()
    val submittingApprovals: StateFlow<Set<String>> = tracker.submitting

    private val _canSubmitApprovals = MutableStateFlow(false)
    val canSubmitApprovals: StateFlow<Boolean> = _canSubmitApprovals.asStateFlow()

    fun bindGateway(gateway: DesktopChatGateway?) {
        _canSubmitApprovals.value = gateway is ApprovalSubmittingGateway || gateway is DesktopApprovalSubmitter
    }

    private data class SubmissionTarget(
        val gateway: Any,
        val agentId: String,
        val conversationId: String,
    )

    fun submitApproval(request: ApprovalSubmissionRequest) {
        val target = validateSubmissionTarget(request) ?: return
        tracker.begin(request.requestId, target.conversationId)
        launchSubmission(target, request)
    }

    private fun validateSubmissionTarget(request: ApprovalSubmissionRequest): SubmissionTarget? {
        val gw = request.gateway ?: return null
        if (gw !is ApprovalSubmittingGateway && gw !is DesktopApprovalSubmitter) return null
        val conversation = request.conversation ?: return null
        val agentId = conversation.agentId?.takeIf { it.isNotBlank() } ?: return null
        return SubmissionTarget(gw, agentId, conversation.id)
    }

    private fun launchSubmission(target: SubmissionTarget, request: ApprovalSubmissionRequest) {
        scope.launch {
            try {
                dispatchToGateway(target.gateway, target.agentId, target.conversationId, request)
            } catch (cancelled: CancellationException) {
                clearSubmittedApproval(request.requestId)
                throw cancelled
            } catch (t: Throwable) {
                clearSubmittedApproval(request.requestId)
                onError(t.message ?: t::class.simpleName ?: "Could not submit answer")
            }
        }
    }

    private suspend fun dispatchToGateway(
        gw: Any,
        agentId: String,
        conversationId: String,
        request: ApprovalSubmissionRequest,
    ) {
        val toolCallId = request.toolCallIds.firstOrNull()
        when (gw) {
            is ApprovalSubmittingGateway -> gw.submitApproval(
                agentId = agentId,
                conversationId = conversationId,
                approvalRequestId = request.requestId,
                toolCallId = toolCallId,
                approve = request.approve,
                reason = request.reason,
            )
            is DesktopApprovalSubmitter -> gw.submitApproval(
                DesktopApprovalSubmission(
                    agentId = agentId,
                    conversationId = conversationId,
                    requestId = request.requestId,
                    toolCallId = toolCallId,
                    approve = request.approve,
                    reason = request.reason,
                ),
            )
        }
    }

    fun clearSubmittedApproval(requestId: String) = tracker.clear(requestId)

    fun reconcileSubmittedApprovals(conversationId: String, messages: List<UiMessage>) {
        if (tracker.submitting.value.isEmpty()) return
        tracker.reconcile(conversationId, messages.mapNotNull { it.approvalRequest?.requestId }.toSet())
    }
}
