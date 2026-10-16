package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.approval.ChatApprovalAnswerHost
import com.letta.mobile.data.chat.approval.ChatApprovalAnswerer
import com.letta.mobile.data.chat.approval.ChatApprovalResult
import com.letta.mobile.data.chat.approval.ChatApprovalSubmission
import com.letta.mobile.data.chat.approval.OutboxNotice
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatApprovalStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ChatApprovalController(
    private val scope: CoroutineScope,
    private val coordinator: ChatApprovalCoordinator,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val bannerController: ChatBannerController,
    private val agentId: String,
    private val activeConversationId: () -> String?,
    /** letta-mobile-bzvro.40: the Iroh host's parked requests, whose cards are not in [uiState] itself. */
    private val relay: ChatApprovalRelay? = null,
) {
    /**
     * letta-mobile-by4vm.78/.93: the answer lifecycle and host approval state desktop uses too; the
     * screen lays [approvals] over the timeline with withHostApprovals, which derives the answering row.
     */
    private val answerer = ChatApprovalAnswerer(scope, AnswerHost())
    val approvals: ChatApprovalStateSource get() = answerer

    /** The newest answer submitted; only its delivery ends the typing presence it started. */
    private var presenceOwner: String? = null

    init {
        answerer.bind(relay?.outbox?.queuedRequestIds, relay?.pendingDetails)
        // letta-mobile-bzvro.14: an answer made offline that the host no longer needed is said so.
        relay?.let { owner ->
            scope.launch {
                owner.outbox.notices.collect { notice ->
                    if (notice is OutboxNotice.Dropped) bannerController.showError(DROPPED_OFFLINE_ANSWER)
                }
            }
        }
        scope.launch {
            combine(
                uiState.map { state -> state.messages.mapNotNull { it.approvalRequest?.requestId }.toSet() },
                answerer.pendingApprovalDetails.map { parked -> parked.values.map { it.approvalId }.toSet() },
            ) { timeline, parked -> timeline + parked }
                .distinctUntilChanged()
                .collect { pending ->
                    // Nothing in flight: do not touch activeConversationId, which the owning
                    // view model may still be initialising when this first emission lands.
                    if (answerer.submittingApprovals.value.isEmpty()) return@collect
                    answerer.reconcile(ConversationId(activeConversationId() ?: NO_CONVERSATION), pending)
                }
        }
    }

    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
        activeConversationIdOverride: String? = null,
    ) = submit(ChatApprovalAnswer(requestId, toolCallIds, approve, reason), activeConversationIdOverride?.let(::ConversationId))

    /** letta-mobile-bzvro.40: an answer from the shared card, always-allow rule ids included. */
    fun submit(answer: ChatApprovalAnswer, conversationOverride: ConversationId? = null) {
        val conversation = conversationOverride?.value ?: activeConversationId()
        answerer.submit(
            ChatApprovalSubmission(answer, ConversationId(conversation ?: NO_CONVERSATION)) {
                coordinator.submitApproval(agentId, conversation, answer)
            },
        )
    }

    /** How each step of an answer lands on the typing presence and the banner. */
    private inner class AnswerHost : ChatApprovalAnswerHost {
        override fun onInFlight(answer: ChatApprovalAnswer) {
            presenceOwner = answer.requestId
            uiState.update { it.copy(isStreaming = true, isAgentTyping = true) }
        }

        /** Presence resets; only an answer that went out (or waits in the offline outbox) stays in flight. */
        override fun onAccepted(answer: ChatApprovalAnswer, result: ChatApprovalResult) {
            // letta-mobile-bzvro.40: a relayed card goes now, not at the host's next snapshot; a queued
            // one (letta-mobile-bzvro.14) stays, marked as waiting for the connection.
            if (result == ChatApprovalResult.Submitted) answer.toolCallIds.forEach { relay?.resolve(it) }
            endPresence(answer)
        }

        override fun onRejected(answer: ChatApprovalAnswer, message: String) {
            bannerController.showError(message)
            endPresence(answer)
        }

        override fun onCancelled(answer: ChatApprovalAnswer) = endPresence(answer)

        private fun endPresence(answer: ChatApprovalAnswer) {
            if (presenceOwner != answer.requestId) return
            presenceOwner = null
            uiState.update { it.copy(isStreaming = false, isAgentTyping = false) }
        }
    }

    private companion object {
        const val NO_CONVERSATION = ""
        const val DROPPED_OFFLINE_ANSWER = "An approval you answered offline was already resolved, so it was not sent."
    }
}
