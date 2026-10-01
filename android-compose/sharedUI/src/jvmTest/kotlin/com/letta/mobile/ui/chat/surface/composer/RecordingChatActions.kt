package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand

/** A [ChatActions] that records every call; [onText] lets a test feed the draft back. */
class RecordingChatActions(private val onText: (String) -> Unit = {}) : ChatActions {
    val calls = mutableListOf<String>()
    val texts = mutableListOf<String>()
    val commandsRun = mutableListOf<ChatComposerCommand>()
    val uninstalled = mutableListOf<ChatComposerCommand>()
    val models = mutableListOf<Pair<String, ReasoningEffortChoice>>()

    fun count(name: String): Int = calls.count { it == name }

    override fun updateComposerText(text: String) {
        calls += "updateComposerText"
        texts += text
        onText(text)
    }

    override fun send() { calls += "send" }
    override fun sendText(text: String) { calls += "sendText" }
    override fun attachImage(image: MessageContentPart.Image) { calls += "attachImage" }
    override fun removeAttachment(index: Int) { calls += "removeAttachment:$index" }
    override fun reportComposerError(message: String) { calls += "reportComposerError" }
    override fun clearComposerError() { calls += "clearComposerError" }

    override fun runComposerCommand(command: ChatComposerCommand) {
        calls += "runComposerCommand"
        commandsRun += command
    }

    override fun uninstallComposerCommand(command: ChatComposerCommand) {
        calls += "uninstallComposerCommand"
        uninstalled += command
    }

    override fun stopRun() { calls += "stopRun" }
    override fun rerun(message: UiMessage) { calls += "rerun" }
    override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) {
        calls += "submitApproval"
    }
    override fun submitA2uiAction(action: A2uiAction) { calls += "submitA2uiAction" }
    override fun dismissA2uiSurface(surfaceId: String) { calls += "dismissA2uiSurface" }
    override fun markA2uiSnackbarShown(id: Long) { calls += "markA2uiSnackbarShown" }
    override fun cancelQueuedSend(id: QueuedSendId) { calls += "cancelQueuedSend:${id.value}" }
    override fun sendQueuedNow(id: QueuedSendId) { calls += "sendQueuedNow:${id.value}" }
    override fun resumeSendQueue() { calls += "resumeSendQueue" }
    override fun toggleRunCollapsed(runId: String) { calls += "toggleRunCollapsed" }
    override fun toggleReasoningExpanded(messageId: String) { calls += "toggleReasoningExpanded" }
    override fun loadOlderMessages() { calls += "loadOlderMessages" }
    override fun releaseOlderMessages() { calls += "releaseOlderMessages" }
    override fun expandTruncatedToolResult(messageId: String) { calls += "expandTruncatedToolResult" }
    override fun retryLoad() { calls += "retryLoad" }
    override fun clearError() { calls += "clearError" }
    override fun setFontScale(scale: Float) { calls += "setFontScale" }

    override fun selectModel(handle: String, effort: ReasoningEffortChoice) {
        calls += "selectModel"
        models += handle to effort
    }

    override fun changeWorkingDirectory(path: String) { calls += "changeWorkingDirectory" }
    override fun updateSearchQuery(query: String) { calls += "updateSearchQuery" }
    override fun clearSearch() { calls += "clearSearch" }
}
