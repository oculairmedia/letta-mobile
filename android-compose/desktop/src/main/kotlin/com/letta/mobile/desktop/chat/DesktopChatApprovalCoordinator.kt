package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.approval.ChatApprovalAnswerHost
import com.letta.mobile.data.chat.approval.ChatApprovalAnswerer
import com.letta.mobile.data.chat.approval.ChatApprovalResult
import com.letta.mobile.data.chat.approval.ChatApprovalSubmission
import com.letta.mobile.data.chat.runtime.AlwaysAllowAnswer
import com.letta.mobile.data.chat.runtime.ApprovalQueueSource
import com.letta.mobile.data.chat.runtime.ApprovalSubmittingGateway
import com.letta.mobile.data.chat.runtime.PendingApprovalSource
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatApprovalStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ApprovalSubmissionRequest(
    val gateway: DesktopChatGateway?,
    val conversation: DesktopConversationSummary?,
    val answer: ChatApprovalAnswer,
)

/**
 * letta-mobile-by4vm.97: the approval state desktop's session port reads: the shared
 * [ChatApprovalStateSource] the port lays over the timeline, plus whether the active gateway can
 * answer at all (demo / HTTP-only gateways cannot, so the page hides the buttons).
 */
interface DesktopChatApprovalState : ChatApprovalStateSource {
    val canSubmitApprovals: StateFlow<Boolean>
}

/**
 * Binds the active gateway's approval surface (can it answer, what it parked, what waits in its
 * outbox) and delivers answers over it. The answer lifecycle itself - in flight, released, reconciled
 * against the timeline - is the shared [ChatApprovalAnswerer] Android's chat screen uses too
 * (letta-mobile-by4vm.78).
 */
internal class DesktopChatApprovalCoordinator(
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) : DesktopChatApprovalState {
    private val answerer = ChatApprovalAnswerer(
        scope,
        object : ChatApprovalAnswerHost {
            override fun onRejected(answer: ChatApprovalAnswer, message: String) = onError(message)
        },
    )
    override val submittingApprovals: StateFlow<Set<String>> = answerer.submitting

    private val _canSubmitApprovals = MutableStateFlow(false)
    override val canSubmitApprovals: StateFlow<Boolean> = _canSubmitApprovals.asStateFlow()

    private val _queuedApprovals = MutableStateFlow<Set<String>>(emptySet())

    /** letta-mobile-bzvro.14: requests answered offline and waiting in the bound gateway's outbox. */
    override val queuedApprovals: StateFlow<Set<String>> = _queuedApprovals.asStateFlow()
    private var queueJob: Job? = null

    private val _pendingApprovalDetails = MutableStateFlow<Map<String, PendingApprovalDetails>>(emptyMap())

    /** What each parked control request of the bound gateway offered, by tool call id (empty when it reports none). */
    override val pendingApprovalDetails: StateFlow<Map<String, PendingApprovalDetails>> = _pendingApprovalDetails.asStateFlow()
    private var detailsJob: Job? = null

    fun bindGateway(gateway: DesktopChatGateway?) {
        _canSubmitApprovals.value = gateway is ApprovalSubmittingGateway || gateway is DesktopApprovalSubmitter
        bindQueue(gateway)
        detailsJob?.cancel()
        _pendingApprovalDetails.value = emptyMap()
        val source = gateway as? PendingApprovalSource ?: return
        detailsJob = scope.launch {
            source.pendingApprovalDetails.collect {
                _pendingApprovalDetails.value = it
                answerer.releaseAnsweredCalls(it)
            }
        }
    }

    private fun bindQueue(gateway: DesktopChatGateway?) {
        queueJob?.cancel()
        _queuedApprovals.value = emptySet()
        val source = gateway as? ApprovalQueueSource ?: return
        queueJob = scope.launch { source.queuedApprovalRequestIds.collect { _queuedApprovals.value = it } }
    }

    private data class SubmissionTarget(
        val gateway: DesktopChatGateway,
        val agentId: String,
        val conversationId: String,
    )

    /** Answers over the request's gateway; a request without one that can answer is dropped silently. */
    fun submitApproval(request: ApprovalSubmissionRequest) {
        val target = validateSubmissionTarget(request) ?: return
        answerer.submit(
            ChatApprovalSubmission(request.answer, ConversationId(target.conversationId)) {
                dispatchToGateway(target, request.answer)
                ChatApprovalResult.Submitted
            },
        )
    }

    private fun validateSubmissionTarget(request: ApprovalSubmissionRequest): SubmissionTarget? {
        val gw = request.gateway ?: return null
        if (gw !is ApprovalSubmittingGateway && gw !is DesktopApprovalSubmitter) return null
        val conversation = request.conversation ?: return null
        val agentId = conversation.agentId?.takeIf { it.isNotBlank() } ?: return null
        return SubmissionTarget(gw, agentId, conversation.id)
    }

    private suspend fun dispatchToGateway(target: SubmissionTarget, answer: ChatApprovalAnswer) {
        val gw = target.gateway
        // An always-allow targets exactly the call whose card offered the rule, not the first of the row.
        val toolCallId = answer.suggestionBinding?.toolCallId ?: answer.toolCallIds.firstOrNull()
        val alwaysAllow = answer.alwaysAllow(target)
        when {
            // letta-mobile-bzvro.40: an Iroh gateway carries the rule to its host, bound to the request the card drew.
            gw is ApprovalSubmittingGateway && alwaysAllow != null -> gw.submitAlwaysAllow(alwaysAllow)
            gw is ApprovalSubmittingGateway -> gw.submitApproval(
                agentId = target.agentId,
                conversationId = target.conversationId,
                approvalRequestId = answer.requestId,
                toolCallId = toolCallId,
                approve = answer.approve,
                reason = answer.reason,
            )
            gw is DesktopApprovalSubmitter -> gw.submitApproval(
                DesktopApprovalSubmission(
                    agentId = target.agentId,
                    conversationId = target.conversationId,
                    requestId = answer.requestId,
                    toolCallId = toolCallId,
                    approve = answer.approve,
                    reason = answer.reason,
                    selectedSuggestionIds = answer.selectedSuggestionIds,
                    suggestionBinding = answer.suggestionBinding,
                ),
            )
        }
    }

    /** The always-allow [this] asks for, or null for a plain decision. */
    private fun ChatApprovalAnswer.alwaysAllow(target: SubmissionTarget): AlwaysAllowAnswer? {
        val binding = suggestionBinding ?: return null
        if (selectedSuggestionIds.isEmpty()) return null
        return AlwaysAllowAnswer(target.agentId, target.conversationId, binding, selectedSuggestionIds)
    }

    fun reconcileSubmittedApprovals(conversation: ConversationId, messages: List<UiMessage>) {
        answerer.reconcile(conversation, messages.mapNotNull { it.approvalRequest?.requestId }.toSet())
    }
}
