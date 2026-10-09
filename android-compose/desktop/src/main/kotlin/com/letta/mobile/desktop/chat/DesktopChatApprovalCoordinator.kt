package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.approval.ApprovalSubmissionTracker
import com.letta.mobile.data.chat.runtime.ApprovalSubmittingGateway
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import com.letta.mobile.data.runtime.ApprovalBinding
import com.letta.mobile.data.runtime.PendingApprovalDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    val selectedSuggestionIds: List<String> = emptyList(),
    val suggestionBinding: ApprovalBinding? = null,
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

    private val _pendingApprovalDetails = MutableStateFlow<Map<String, PendingApprovalDetails>>(emptyMap())

    /** What each parked control request of the bound gateway offered, by tool call id (empty when it reports none). */
    val pendingApprovalDetails: StateFlow<Map<String, PendingApprovalDetails>> = _pendingApprovalDetails.asStateFlow()
    private var detailsJob: Job? = null

    /**
     * Requests answered one parallel call at a time: request id to (the call answered, its sibling
     * calls). The tracker holds a request "submitting" until the whole request is decided; once the
     * answered call's gate has resolved and a sibling is parked in its place, the card is for that
     * sibling and must be actionable again.
     */
    private val answeredCalls = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Set<String>>>()

    private fun releaseAnsweredCalls(parked: Map<String, PendingApprovalDetails>) {
        answeredCalls.entries.removeIf { (requestId, calls) ->
            val release = calls.first !in parked && calls.second.any { it in parked }
            if (release) tracker.clear(requestId)
            release
        }
    }

    fun bindGateway(gateway: DesktopChatGateway?) {
        _canSubmitApprovals.value = gateway is ApprovalSubmittingGateway || gateway is DesktopApprovalSubmitter
        detailsJob?.cancel()
        _pendingApprovalDetails.value = emptyMap()
        val source = gateway as? DesktopPendingApprovalSource ?: return
        detailsJob = scope.launch {
            source.pendingApprovalDetails.collect {
                _pendingApprovalDetails.value = it
                releaseAnsweredCalls(it)
            }
        }
    }

    private data class SubmissionTarget(
        val gateway: DesktopChatGateway,
        val agentId: String,
        val conversationId: String,
    )

    fun submitApproval(request: ApprovalSubmissionRequest) {
        val target = validateSubmissionTarget(request) ?: return
        tracker.begin(request.requestId, target.conversationId)
        request.suggestionBinding?.let { answeredCalls[request.requestId] = it.toolCallId to (request.toolCallIds - it.toolCallId).toSet() }
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
        gw: DesktopChatGateway,
        agentId: String,
        conversationId: String,
        request: ApprovalSubmissionRequest,
    ) {
        // An always-allow targets exactly the call whose card offered the rule, not the first of the row.
        val toolCallId = request.suggestionBinding?.toolCallId ?: request.toolCallIds.firstOrNull()
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
                    selectedSuggestionIds = request.selectedSuggestionIds,
                    suggestionBinding = request.suggestionBinding,
                ),
            )
        }
    }

    fun clearSubmittedApproval(requestId: String) {
        answeredCalls.remove(requestId)
        tracker.clear(requestId)
    }

    fun reconcileSubmittedApprovals(conversationId: String, messages: List<UiMessage>) {
        if (tracker.submitting.value.isEmpty()) return
        tracker.reconcile(conversationId, messages.mapNotNull { it.approvalRequest?.requestId }.toSet())
    }
}
