package com.letta.mobile.feature.chat.coordination

import android.util.Log
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.a2ui.PendingToolApprovalRegistry
import com.letta.mobile.data.a2ui.ToolApprovalAdmission
import com.letta.mobile.data.a2ui.ToolApprovalRef
import com.letta.mobile.data.model.A2uiSurfaceId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.letta.mobile.ui.chat.render.ChatUiState

internal class AdminChatA2uiCoordinator(
    private val scope: CoroutineScope,
    private val wsChatBridge: WsChatBridge,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val chatBannerController: ChatBannerController,
    private val activeConversationId: () -> String?,
    private val chatApprovalController: ChatApprovalController,
) {
    private val a2uiSurfaceManager = A2uiSurfaceManager()

    /** letta-mobile-by4vm.2: the approvals this screen shows as pending; the only way an A2UI answer is submitted. */
    private val pendingToolApprovals = PendingToolApprovalRegistry()
    private var a2uiConversationId: String? = null
    private var a2uiHistorySignature: Int? = null
    private var a2uiHistoryAppliedCount: Int = 0
    private var a2uiHistoryAppliedPrefix: List<A2uiMessage>? = null

    init {
        observeA2uiSurfaceState()
        observeTurnEnds()
        observePendingToolApprovals()
    }

    private fun observeA2uiSurfaceState() {
        scope.launch {
            a2uiSurfaceManager.surfaces.collect { surfaces ->
                publishA2uiSurfaces(surfaces)
            }
        }
    }

    private fun publishA2uiSurfaces(surfaces: Map<String, A2uiSurfaceState>) {
        val next = surfaces.toPersistentMap()
        uiState.update { current ->
            if (current.a2uiSurfaces == next) {
                current
            } else {
                current.copy(a2uiSurfaces = next)
            }
        }
    }

    fun ensureA2uiConversation(conversationId: String) {
        if (a2uiConversationId == conversationId) return
        a2uiConversationId?.let { pendingToolApprovals.expire(ConversationId(it)) }
        a2uiConversationId = conversationId
        a2uiHistorySignature = null
        a2uiHistoryAppliedCount = 0
        a2uiHistoryAppliedPrefix = null
        a2uiSurfaceManager.clear()
        uiState.update { it.copy(a2uiSurfaces = persistentMapOf()) }
    }

    fun syncA2uiHistorySnapshot(
        conversationId: String,
        messages: List<A2uiMessage>,
    ): Map<String, A2uiSurfaceState> {
        ensureA2uiConversation(conversationId)
        val signature = messages.hashCode()
        if (signature != a2uiHistorySignature) {
            val appliedPrefix = a2uiHistoryAppliedPrefix
            val isAppend = messages.size >= a2uiHistoryAppliedCount &&
                appliedPrefix != null &&
                messages.subList(0, a2uiHistoryAppliedCount) == appliedPrefix

            if (isAppend) {
                val trailing = messages.subList(a2uiHistoryAppliedCount, messages.size)
                if (trailing.isNotEmpty()) {
                    a2uiSurfaceManager.applyMessages(trailing)
                }
            } else {
                a2uiSurfaceManager.replaceWith(messages)
            }

            a2uiHistorySignature = signature
            a2uiHistoryAppliedCount = messages.size
            a2uiHistoryAppliedPrefix = messages.toList()
        }
        return a2uiSurfaceManager.surfaces.value
    }

    private fun observeTurnEnds() {
        scope.launch {
            wsChatBridge.events.collect { event ->
                // letta-mobile-by4vm.2: an approval cannot outlive its turn.
                if (event is WsTimelineEvent.TurnDone) activeConversation()?.let(pendingToolApprovals::expire)
            }
        }
    }

    /** Registers exactly the approvals the client projected from its own timeline as pending. */
    private fun observePendingToolApprovals() {
        scope.launch {
            uiState
                .map { state ->
                    state.messages.mapNotNull { it.approvalRequest }.flatMapTo(mutableSetOf()) { request ->
                        request.toolCalls.mapNotNull { ToolApprovalRef.of(request.requestId, it.toolCallId) }
                    }
                }
                .distinctUntilChanged()
                .collect { pending ->
                    val conversation = activeConversation() ?: return@collect
                    pendingToolApprovals.sync(conversation, pending)
                }
        }
    }

    private fun activeConversation(): ConversationId? = activeConversationId()?.takeIf { it.isNotBlank() }?.let(::ConversationId)

    fun markA2uiActionSnackbarShown(id: Long) {
        uiState.update { current ->
            if (current.a2uiActionSnackbar?.id == id) {
                current.copy(a2uiActionSnackbar = null)
            } else {
                current
            }
        }
    }

    fun dismissA2uiSurface(surfaceId: String) {
        a2uiSurfaceManager.dismissSurface(A2uiSurfaceId(surfaceId))
        publishA2uiSurfaces(a2uiSurfaceManager.surfaces.value)
    }

    fun submitA2uiAction(action: A2uiAction) {
        val resolvedAction = if (action.conversationId.isNullOrBlank()) {
            val currentConversationId = activeConversationId()
            if (currentConversationId.isNullOrBlank()) {
                chatBannerController.showComposerError("Couldn't send action. No active conversation is available.")
                return
            }
            action.copy(conversationId = currentConversationId)
        } else {
            action
        }
        if (submitA2uiToolApprovalViaRest(resolvedAction)) return
        // No transport carries other card actions to the agent (the shim's user_action frame is
        // gone, by4vm.111): only tool approvals are answerable from a card.
        Log.i(TAG, "unroutable A2UI action surfaceId=${resolvedAction.surfaceId} event=${resolvedAction.name}")
        chatBannerController.showComposerError("Couldn't send action. Only approval cards can be answered here.")
    }

    /**
     * letta-mobile-by4vm.2: any `tool_approval_response` ends here - submitted only when the shared
     * registry admits it, otherwise dropped (logged by the registry). It never falls through to the
     * WS action path, where the server would match it against the pending approval unchecked.
     */
    private fun submitA2uiToolApprovalViaRest(action: A2uiAction): Boolean {
        when (val admission = pendingToolApprovals.admit(action, activeConversation()) ?: return false) {
            is ToolApprovalAdmission.Rejected ->
                chatBannerController.showComposerError("This approval could not be verified, so it was not sent.")
            is ToolApprovalAdmission.Admitted -> {
                val submission = admission.submission
                chatApprovalController.submitApproval(
                    requestId = submission.ref.approvalId.value,
                    toolCallIds = listOf(submission.ref.callId.value),
                    approve = submission.approve,
                    reason = submission.reason,
                    activeConversationIdOverride = admission.conversation.value,
                )
            }
        }
        return true
    }

    private companion object {
        const val TAG = "AdminChatA2uiCoordinator"
    }
}
