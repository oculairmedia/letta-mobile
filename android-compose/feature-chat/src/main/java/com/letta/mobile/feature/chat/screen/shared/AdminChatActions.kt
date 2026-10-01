package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand

/**
 * letta-mobile-bglj6.1: the shared chat page's [ChatActions], each forwarded to the
 * [AdminChatViewModel] method the legacy Android chat screen already calls.
 *
 * @param onOpenBugReport raised when the composer resolves `/bug` (project chats), which the
 *   legacy screen answers by opening the bug report sheet.
 */
internal class AdminChatActions(
    private val viewModel: AdminChatViewModel,
    private val onOpenBugReport: () -> Unit,
) : ChatActions {

    private fun forward(effect: ChatComposerEffect?) {
        if (effect == ChatComposerEffect.OpenBugReport) onOpenBugReport()
    }

    // Composer
    override fun updateComposerText(text: String) = forward(viewModel.handleComposerTextChanged(text))

    override fun send() = forward(viewModel.submitComposer(viewModel.composerState.value.inputText))

    override fun sendText(text: String) = viewModel.sendMessage(text)

    override fun attachImage(image: MessageContentPart.Image) {
        viewModel.addAttachment(image)
    }

    override fun removeAttachment(index: Int) = viewModel.removeAttachment(index)

    override fun reportComposerError(message: String) = viewModel.reportComposerError(message)

    override fun clearComposerError() = viewModel.clearComposerError()

    override fun runComposerCommand(command: ChatComposerCommand) {
        slashCommandFor(command)?.let(viewModel::selectSlashCommand)
    }

    override fun uninstallComposerCommand(command: ChatComposerCommand) {
        slashCommandFor(command)?.let(viewModel::uninstallSlashCommand)
    }

    private fun slashCommandFor(command: ChatComposerCommand) =
        AdminChatComposerMapping.findSlashCommand(viewModel.composerState.value.slashCommands, command)

    // Run
    override fun stopRun() = viewModel.interruptRun()

    override fun rerun(message: UiMessage) = viewModel.rerunMessage(message)

    override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) =
        viewModel.submitApproval(requestId, toolCallIds, approve, reason)

    override fun submitA2uiAction(action: A2uiAction) = viewModel.submitA2uiAction(action)

    override fun dismissA2uiSurface(surfaceId: String) = viewModel.dismissA2uiSurface(surfaceId)

    override fun markA2uiSnackbarShown(id: Long) = viewModel.markA2uiActionSnackbarShown(id)

    // Send queue
    override fun cancelQueuedSend(id: QueuedSendId) = viewModel.queuedSendActions.onCancel(id)

    override fun sendQueuedNow(id: QueuedSendId) = viewModel.queuedSendActions.onSendNow(id)

    override fun resumeSendQueue() = viewModel.queuedSendActions.onResume()

    // Timeline
    override fun toggleRunCollapsed(runId: String) = viewModel.toggleRunCollapsed(runId)

    override fun toggleReasoningExpanded(messageId: String) = viewModel.toggleReasoningExpanded(messageId)

    override fun loadOlderMessages() = viewModel.loadOlderMessages()

    override fun releaseOlderMessages() = viewModel.releaseOlderMessages()

    override fun expandTruncatedToolResult(messageId: String) {
        viewModel.onTruncatedToolResultExpanded(messageId)
    }

    override fun retryLoad() = viewModel.retryConversationLoad()

    override fun clearError() = viewModel.clearError()

    override fun setFontScale(scale: Float) = viewModel.setChatFontScale(scale)

    // Conversation settings
    override fun selectModel(handle: String, effort: ReasoningEffortChoice) {
        viewModel.updateActiveAgentModel(handle, AdminChatComposerMapping.effortSelection(effort))
    }

    /** Android's backend has no working directory; [AdminChatSessionPort] reports the capability off. */
    override fun changeWorkingDirectory(path: String) = Unit

    // Search
    override fun updateSearchQuery(query: String) = viewModel.updateChatSearchQuery(query)

    override fun clearSearch() = viewModel.clearChatSearch()
}
