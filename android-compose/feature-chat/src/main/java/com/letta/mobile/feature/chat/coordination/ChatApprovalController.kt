package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.approval.ApprovalSubmissionTracker
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CancellationException
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
     * The shared in-flight tracker (letta-mobile-2dsi5.4), the same one desktop uses. An answer stays
     * in flight until this conversation's timeline stops showing the request as pending, so a card
     * cannot turn actionable again between a successful submit and the server's decision.
     */
    private val tracker = ApprovalSubmissionTracker()

    init {
        scope.launch {
            combine(
                uiState.map { state -> state.messages.mapNotNull { it.approvalRequest?.requestId }.toSet() },
                relay?.pendingDetails?.map { parked -> parked.values.map { it.approvalId }.toSet() } ?: flowOf(emptySet()),
            ) { timeline, parked -> timeline + parked }
                .distinctUntilChanged()
                .collect { pending ->
                    if (tracker.submitting.value.isEmpty()) return@collect
                    val conversationId = activeConversationId() ?: NO_CONVERSATION
                    if (tracker.reconcile(conversationId, pending).isNotEmpty()) publishInFlight()
                }
        }
    }

    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
        activeConversationIdOverride: String? = null,
    ) = submit(ChatApprovalAnswer(requestId, toolCallIds, approve, reason), activeConversationIdOverride)

    /** letta-mobile-bzvro.40: an answer from the shared card, always-allow rule ids included. */
    fun submit(answer: ChatApprovalAnswer, activeConversationIdOverride: String? = null) {
        val requestId = answer.requestId
        val conversationId = activeConversationIdOverride ?: activeConversationId()
        scope.launch {
            markApprovalInFlight(requestId, conversationId)
            val submitted = try {
                accepted(coordinator.submitApproval(agentId, conversationId, answer))
            } catch (cancelled: CancellationException) {
                failApprovalInFlight(requestId)
                throw cancelled
            } catch (e: Exception) {
                bannerController.showError(e.message ?: "Failed to submit approval")
                false
            }
            // Presence flags reset as they always have; only a successful answer keeps its card
            // in flight, until the timeline reconciles it. Anything else is actionable again now.
            if (submitted) {
                // letta-mobile-bzvro.40: a relayed card goes now, not at the host's next snapshot.
                answer.toolCallIds.forEach { relay?.resolve(it) }
                settleApprovalPresence(requestId)
            } else {
                failApprovalInFlight(requestId)
            }
        }
    }

    /** Whether [result] means the answer went out; anything else is shown on the banner. */
    private fun accepted(result: ChatApprovalResult): Boolean {
        val error = when (result) {
            ChatApprovalResult.Submitted -> return true
            ChatApprovalResult.MissingActiveAgent -> "No active agent available for approval"
            ChatApprovalResult.MissingActiveConversation -> "No active conversation available for approval"
            is ChatApprovalResult.Failed -> result.message
        }
        bannerController.showError(error)
        return false
    }

    private fun settleApprovalPresence(requestId: String) {
        uiState.update {
            if (it.activeApprovalRequestId != requestId) it else it.copy(isStreaming = false, isAgentTyping = false)
        }
    }

    private fun markApprovalInFlight(requestId: String, conversationId: String?) {
        tracker.begin(requestId, conversationId ?: NO_CONVERSATION)
        uiState.update {
            it.copy(
                isStreaming = true,
                isAgentTyping = true,
                activeApprovalRequestId = requestId,
            )
        }
    }

    private fun failApprovalInFlight(requestId: String) {
        tracker.clear(requestId)
        uiState.update {
            if (it.activeApprovalRequestId != requestId) {
                it
            } else {
                it.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    activeApprovalRequestId = tracker.latest,
                )
            }
        }
    }

    /** The timeline decided some answers: point the UI at whatever is still in flight. */
    private fun publishInFlight() {
        uiState.update { it.copy(activeApprovalRequestId = tracker.latest) }
    }

    private companion object {
        const val NO_CONVERSATION = ""
    }
}
