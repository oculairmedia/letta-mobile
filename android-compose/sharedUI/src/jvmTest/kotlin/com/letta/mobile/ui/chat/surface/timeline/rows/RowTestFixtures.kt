package com.letta.mobile.ui.chat.surface.timeline.rows

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import kotlinx.collections.immutable.toImmutableSet

/** Records the row-level intents; everything else is a no-op. */
internal class RecordingChatActions : ChatActions {
    val reruns = mutableListOf<UiMessage>()
    val approvals = mutableListOf<Approval>()
    val toggledRuns = mutableListOf<String>()
    val toggledReasoning = mutableListOf<String>()
    val expandedTruncations = mutableListOf<String>()

    data class Approval(val requestId: String, val toolCallIds: List<String>, val approve: Boolean, val reason: String?)

    override fun rerun(message: UiMessage) {
        reruns += message
    }

    override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) {
        approvals += Approval(requestId, toolCallIds, approve, reason)
    }

    override fun toggleRunCollapsed(runId: String) {
        toggledRuns += runId
    }

    override fun toggleReasoningExpanded(messageId: String) {
        toggledReasoning += messageId
    }

    override fun expandTruncatedToolResult(messageId: String) {
        expandedTruncations += messageId
    }

    override fun updateComposerText(text: String) = Unit
    override fun send() = Unit
    override fun sendText(text: String) = Unit
    override fun attachImage(image: MessageContentPart.Image) = Unit
    override fun removeAttachment(index: Int) = Unit
    override fun reportComposerError(message: String) = Unit
    override fun clearComposerError() = Unit
    override fun runComposerCommand(command: ChatComposerCommand) = Unit
    override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit
    override fun stopRun() = Unit
    override fun submitA2uiAction(action: A2uiAction) = Unit
    override fun dismissA2uiSurface(surfaceId: String) = Unit
    override fun markA2uiSnackbarShown(id: Long) = Unit
    override fun cancelQueuedSend(id: QueuedSendId) = Unit
    override fun sendQueuedNow(id: QueuedSendId) = Unit
    override fun resumeSendQueue() = Unit
    override fun loadOlderMessages() = Unit
    override fun releaseOlderMessages() = Unit
    override fun retryLoad() = Unit
    override fun clearError() = Unit
    override fun setFontScale(scale: Float) = Unit
    override fun selectModel(handle: String, effort: ReasoningEffortChoice) = Unit
    override fun changeWorkingDirectory(path: String) = Unit
    override fun updateSearchQuery(query: String) = Unit
    override fun clearSearch() = Unit
}

internal fun rowContext(
    capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
    collapsedRunIds: Set<String> = emptySet(),
    expandedReasoning: Set<String> = emptySet(),
    activeApprovalRequestId: String? = null,
): ChatRowContext = ChatRowContext(
    itemState = ChatRenderItemState(
        isStreaming = false,
        activeApprovalRequestId = activeApprovalRequestId,
        collapsedRunIds = collapsedRunIds.toImmutableSet(),
        expandedReasoningMessageIds = expandedReasoning.toImmutableSet(),
    ),
    capabilities = capabilities,
)

internal fun rowCallbacks(
    actions: ChatActions = RecordingChatActions(),
    host: ChatSurfaceHost = ChatSurfaceHost(),
    resolveAgentName: (String) -> String? = { null },
    openSubagent: ((ChatSubagentTarget) -> Unit)? = null,
): ChatRowCallbacks = ChatRowCallbacks(
    actions = actions,
    host = host,
    onImageTap = { _, _ -> },
    resolveAgentName = resolveAgentName,
    openSubagent = openSubagent,
)

internal fun message(
    id: String,
    role: String,
    content: String,
    timestamp: String = "2026-07-19T12:00:00Z",
) = UiMessage(id = id, role = role, content = content, timestamp = timestamp)
