package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.chat.branch.BranchSlashCommand
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.feature.chat.coordination.ChatConversationBranching
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.session.A2uiSnackbarId
import com.letta.mobile.ui.chat.session.A2uiSurfaceId
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatExtensionActionState
import com.letta.mobile.ui.chat.session.ChatExtensionActions
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.chat.session.TimelineChatSessionOptions

/**
 * letta-mobile-bglj6.1: the shared chat page's actions, each forwarded to the [AdminChatViewModel]
 * method the legacy Android chat screen already calls; the extension points ([options]) answer the
 * chips, the send queue, the composer's commands, A2UI actions and branching (letta-mobile-by4vm.98.4).
 *
 * @param onOpenBugReport raised when the composer resolves `/bug` (project chats), which the
 *   legacy screen answers by opening the bug report sheet.
 */
internal class AdminChatActions(
    private val viewModel: AdminChatViewModel,
    options: TimelineChatSessionOptions,
    private val state: ChatExtensionActionState,
    private val onOpenBugReport: () -> Unit,
    /** letta-mobile-7mys7: where `/fork [name]` goes; null where the page cannot fork. */
    private val branching: ChatConversationBranching.Page? = null,
) : ChatExtensionActions(options, state) {

    private fun forward(effect: ChatComposerEffect?) {
        if (effect == ChatComposerEffect.OpenBugReport) onOpenBugReport()
    }

    // Composer
    override fun updateComposerText(text: String) = forward(viewModel.handleComposerTextChanged(text))

    override fun send() {
        val text = viewModel.composerState.value.inputText
        if (!runBranchCommand(text)) forward(viewModel.submitComposer(text))
    }

    /**
     * letta-mobile-7mys7: letta-code's `/fork [name]` (a terminal command the App Server does not
     * run) forks this conversation instead of reaching the agent as text. True when it did.
     */
    private fun runBranchCommand(text: String): Boolean {
        val fork = BranchSlashCommand.parse(text) as? BranchSlashCommand.Fork ?: return false
        val branches = branching ?: return false
        branches.forkConversation(fork.name, state.branchView())
        viewModel.handleComposerTextChanged("")
        return true
    }

    override fun sendText(text: String) = viewModel.sendMessage(text)

    override fun attachImage(image: MessageContentPart.Image) {
        viewModel.addAttachment(image)
    }

    override fun removeAttachment(index: Int) = viewModel.removeAttachment(index)

    override fun reportComposerError(message: String) = viewModel.reportComposerError(message)

    override fun clearComposerError() = viewModel.clearComposerError()

    // Run
    override fun stopRun() = viewModel.interruptRun()

    override fun rerun(message: UiMessage) = viewModel.rerunMessage(message)

    override fun submitApproval(answer: ChatApprovalAnswer) = viewModel.submitApproval(answer)

    override fun dismissA2uiSurface(surfaceId: A2uiSurfaceId) = viewModel.dismissA2uiSurface(surfaceId.value)

    override fun markA2uiSnackbarShown(id: A2uiSnackbarId) = viewModel.markA2uiActionSnackbarShown(id.value)

    // Timeline: run expansion and the error stay the ViewModel's (saved across process death).
    override fun toggleRunCollapsed(runId: ChatRunId) = viewModel.toggleRunCollapsed(runId.value)

    override fun toggleReasoningExpanded(messageId: ChatMessageId) = viewModel.toggleReasoningExpanded(messageId.value)

    // letta-mobile-by4vm.15: the canonical paged timeline loads older pages itself. Truncated-preview
    // expansion lived on the deleted legacy loop and had no route on the Iroh transport (no getToolReturn).
    override fun loadOlderMessages() = Unit

    override fun releaseOlderMessages() = Unit

    override fun expandTruncatedToolResult(messageId: ChatMessageId) = Unit

    override fun retryLoad() = viewModel.retryConversationLoad()

    override fun clearError() = viewModel.clearError()

    override fun setFontScale(scale: Float) = viewModel.setChatFontScale(scale)

    // Search
    override fun updateSearchQuery(query: String) = viewModel.updateChatSearchQuery(query)

    override fun clearSearch() = viewModel.clearChatSearch()

    // Goal
    override fun refreshGoalStatus() = viewModel.refreshGoalStatus()

    override fun continueGoal() = viewModel.continueGoal()
}
