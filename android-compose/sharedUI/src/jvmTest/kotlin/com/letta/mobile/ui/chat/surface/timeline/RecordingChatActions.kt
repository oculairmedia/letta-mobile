package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand

/** Records the timeline-relevant intents; the rest are no-ops (letta-mobile-bglj6.1 tests). */
internal class RecordingChatActions : ChatActions {
    val sent = mutableListOf<String>()
    var retries = 0
    var loadOlderCalls = 0
    var releaseOlderCalls = 0
    var clearedErrors = 0
    val fontScales = mutableListOf<Float>()
    val shownSnackbars = mutableListOf<Long>()
    val dismissedSurfaces = mutableListOf<String>()

    override fun sendText(text: String) {
        sent += text
    }

    override fun retryLoad() {
        retries++
    }

    override fun loadOlderMessages() {
        loadOlderCalls++
    }

    override fun releaseOlderMessages() {
        releaseOlderCalls++
    }

    override fun clearError() {
        clearedErrors++
    }

    override fun setFontScale(scale: Float) {
        fontScales += scale
    }

    override fun markA2uiSnackbarShown(id: Long) {
        shownSnackbars += id
    }

    override fun dismissA2uiSurface(surfaceId: String) {
        dismissedSurfaces += surfaceId
    }

    override fun updateComposerText(text: String) = Unit
    override fun send() = Unit
    override fun attachImage(image: MessageContentPart.Image) = Unit
    override fun removeAttachment(index: Int) = Unit
    override fun reportComposerError(message: String) = Unit
    override fun clearComposerError() = Unit
    override fun runComposerCommand(command: ChatComposerCommand) = Unit
    override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit
    override fun stopRun() = Unit
    override fun rerun(message: UiMessage) = Unit
    override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) = Unit
    override fun submitA2uiAction(action: A2uiAction) = Unit
    override fun cancelQueuedSend(id: QueuedSendId) = Unit
    override fun sendQueuedNow(id: QueuedSendId) = Unit
    override fun resumeSendQueue() = Unit
    override fun toggleRunCollapsed(runId: String) = Unit
    override fun toggleReasoningExpanded(messageId: String) = Unit
    override fun expandTruncatedToolResult(messageId: String) = Unit
    override fun selectModel(handle: String, effort: ReasoningEffortChoice) = Unit
    override fun changeWorkingDirectory(path: String) = Unit
    override fun updateSearchQuery(query: String) = Unit
    override fun clearSearch() = Unit
}
