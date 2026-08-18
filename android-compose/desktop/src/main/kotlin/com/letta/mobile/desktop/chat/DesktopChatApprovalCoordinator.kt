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

    fun submitApproval(
        gateway: DesktopChatGateway?,
        conversation: DesktopConversationSummary?,
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) {
        val gw = gateway
        if (gw !is ApprovalSubmittingGateway && gw !is DesktopApprovalSubmitter) return
        if (conversation == null) return
        val agentId = conversation.agentId?.takeIf { it.isNotBlank() } ?: return
        submittedApprovalConversations[requestId] = conversation.id
        _submittingApprovals.update { it + requestId }
        scope.launch {
            try {
                when (gw) {
                    is ApprovalSubmittingGateway -> gw.submitApproval(
                        agentId = agentId,
                        conversationId = conversation.id,
                        approvalRequestId = requestId,
                        toolCallId = toolCallIds.firstOrNull(),
                        approve = approve,
                        reason = reason,
                    )
                    is DesktopApprovalSubmitter -> gw.submitApproval(
                        DesktopApprovalSubmission(
                            agentId = agentId,
                            conversationId = conversation.id,
                            requestId = requestId,
                            toolCallId = toolCallIds.firstOrNull(),
                            approve = approve,
                            reason = reason,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                clearSubmittedApproval(requestId)
                throw cancelled
            } catch (t: Throwable) {
                clearSubmittedApproval(requestId)
                onError(t.message ?: t::class.simpleName ?: "Could not submit answer")
            }
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
