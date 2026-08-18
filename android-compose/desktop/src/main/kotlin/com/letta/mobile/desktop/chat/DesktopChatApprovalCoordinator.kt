package com.letta.mobile.desktop.chat

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
    private val _submittingApprovals = MutableStateFlow<Set<String>>(emptySet())
    val submittingApprovals: StateFlow<Set<String>> = _submittingApprovals.asStateFlow()

    private val _canSubmitApprovals = MutableStateFlow(false)
    val canSubmitApprovals: StateFlow<Boolean> = _canSubmitApprovals.asStateFlow()

    private val submittedApprovalConversations = mutableMapOf<String, String>()

    fun bindGateway(gateway: DesktopChatGateway?) {
        _canSubmitApprovals.value = gateway is ApprovalSubmittingGateway || gateway is DesktopApprovalSubmitter
    }

    fun submitApproval(request: ApprovalSubmissionRequest) {
        val gw = request.gateway ?: return
        if (gw !is ApprovalSubmittingGateway && gw !is DesktopApprovalSubmitter) return
        val conversation = request.conversation ?: return
        val agentId = conversation.agentId?.takeIf { it.isNotBlank() } ?: return

        submittedApprovalConversations[request.requestId] = conversation.id
        _submittingApprovals.update { it + request.requestId }

        scope.launch {
            try {
                dispatchToGateway(gw, agentId, conversation.id, request)
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

    fun clearSubmittedApproval(requestId: String) {
        submittedApprovalConversations.remove(requestId)
        _submittingApprovals.update { it - requestId }
    }

    fun reconcileSubmittedApprovals(conversationId: String, messages: List<UiMessage>) {
        if (submittedApprovalConversations.isEmpty()) return
        val present = messages.mapNotNull { it.approvalRequest?.requestId }.toSet()
        val reconciled = submittedApprovalConversations
            .filterValues { it == conversationId }
            .keys
            .filter { it !in present }
        if (reconciled.isEmpty()) return
        reconciled.forEach { submittedApprovalConversations.remove(it) }
        _submittingApprovals.update { it - reconciled.toSet() }
    }
}
