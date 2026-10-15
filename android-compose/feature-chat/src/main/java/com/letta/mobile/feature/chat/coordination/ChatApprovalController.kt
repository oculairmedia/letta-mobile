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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
     * letta-mobile-by4vm.78: the shared answer lifecycle, the same one desktop uses. An answer stays
     * in flight until this conversation's timeline stops showing the request as pending, so a card
     * cannot turn actionable again between a successful submit and the server's decision.
     */
    private val answerer = ChatApprovalAnswerer(scope, AnswerHost())

    init {
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
                relay?.pendingDetails?.map { parked -> parked.values.map { it.approvalId }.toSet() } ?: flowOf(emptySet()),
            ) { timeline, parked -> timeline + parked }
                .distinctUntilChanged()
                .collect { pending ->
                    val conversation = ConversationId(activeConversationId() ?: NO_CONVERSATION)
                    if (answerer.reconcile(conversation, pending).isNotEmpty()) publishInFlight()
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

    /** How each step of an answer lands on [uiState] and the banner. */
    private inner class AnswerHost : ChatApprovalAnswerHost {
        override fun onInFlight(answer: ChatApprovalAnswer) {
            uiState.update {
                it.copy(isStreaming = true, isAgentTyping = true, activeApprovalRequestId = answer.requestId)
            }
        }

        /**
         * Presence flags reset as they always have; only an answer that went out (or waits in the
         * offline outbox) keeps its card in flight, until the timeline reconciles it.
         */
        override fun onAccepted(answer: ChatApprovalAnswer, result: ChatApprovalResult) {
            // letta-mobile-bzvro.40: a relayed card goes now, not at the host's next snapshot; a queued
            // one (letta-mobile-bzvro.14) stays, marked as waiting for the connection.
            if (result == ChatApprovalResult.Submitted) answer.toolCallIds.forEach { relay?.resolve(it) }
            val requestId = answer.requestId
            uiState.update {
                if (it.activeApprovalRequestId != requestId) it else it.copy(isStreaming = false, isAgentTyping = false)
            }
        }

        override fun onRejected(answer: ChatApprovalAnswer, message: String) {
            bannerController.showError(message)
            release(answer)
        }

        override fun onCancelled(answer: ChatApprovalAnswer) = release(answer)

        /** The answer is no longer in flight: the UI points at whatever still is. */
        private fun release(answer: ChatApprovalAnswer) {
            val requestId = answer.requestId
            uiState.update {
                if (it.activeApprovalRequestId != requestId) {
                    it
                } else {
                    it.copy(isStreaming = false, isAgentTyping = false, activeApprovalRequestId = answerer.latest)
                }
            }
        }
    }

    /** The timeline decided some answers: point the UI at whatever is still in flight. */
    private fun publishInFlight() {
        uiState.update { it.copy(activeApprovalRequestId = answerer.latest) }
    }

    private companion object {
        const val NO_CONVERSATION = ""
        const val DROPPED_OFFLINE_ANSWER = "An approval you answered offline was already resolved, so it was not sent."
    }
}
