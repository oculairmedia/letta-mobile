package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.session.A2uiSnackbarId
import com.letta.mobile.ui.chat.session.A2uiSurfaceId
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatWorkingDirectory
import com.letta.mobile.ui.chat.surface.composer.ChatComposerPanel
import com.letta.mobile.ui.chat.surface.composer.ComposerInputs

/**
 * The chat composer over a draft of its own, for a page that only hands the text on (Home's prompt
 * bar): the same bar, field and Send as the chat page, in the host's idiom (the Touch bar on touch,
 * the prompt card on pointer). Images, the model, commands and voice stay the chat page's, so their
 * controls hide. [onSubmit] gets the trimmed draft, which then clears.
 */
@Composable
internal fun HomePromptComposer(placeholder: String?, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    val submit by rememberUpdatedState(onSubmit)
    val actions = remember { promptBarActions(read = { draft }, write = { draft = it }, submit = { submit(it) }) }
    val composer = ChatComposerUiState(text = draft, canSend = draft.isNotBlank(), placeholder = placeholder)
    ChatComposerPanel(ComposerInputs(composer = composer, actions = actions, capabilities = PromptBarCapabilities), modifier)
}

/** Nothing but the draft: no images, model, history or approvals to offer. */
private val PromptBarCapabilities = ChatSurfaceCapabilities(
    attachImages = false,
    rerun = false,
    approvals = false,
    modelSwitch = false,
    pagedHistory = false,
    fontScale = false,
)

/**
 * [ChatActions] over a draft alone ([read] it, [write] it, [submit] a finished one): typing edits it,
 * Send hands it on; everything else has no control on the prompt bar.
 */
private fun promptBarActions(read: () -> String, write: (String) -> Unit, submit: (String) -> Unit): ChatActions = object : ChatActions {
    override fun updateComposerText(text: String) = write(text)

    override fun send() {
        val text = read().trim()
        if (text.isEmpty()) return
        write("")
        submit(text)
    }

    override fun sendText(text: String) {
        text.trim().takeIf { it.isNotEmpty() }?.let(submit)
    }

    override fun attachImage(image: MessageContentPart.Image) = Unit
    override fun removeAttachment(index: Int) = Unit
    override fun reportComposerError(message: String) = Unit
    override fun clearComposerError() = Unit
    override fun runComposerCommand(command: ChatComposerCommand) = Unit
    override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit
    override fun submitA2uiAction(action: A2uiAction) = Unit
    override fun stopRun() = Unit
    override fun rerun(message: UiMessage) = Unit
    override fun submitApproval(answer: ChatApprovalAnswer) = Unit
    override fun dismissA2uiSurface(surfaceId: A2uiSurfaceId) = Unit
    override fun markA2uiSnackbarShown(id: A2uiSnackbarId) = Unit
    override fun retryLoad() = Unit
    override fun clearError() = Unit
    override fun setFontScale(scale: Float) = Unit
    override fun updateSearchQuery(query: String) = Unit
    override fun clearSearch() = Unit
    override fun refreshGoalStatus() = Unit
    override fun continueGoal() = Unit
    override fun cancelQueuedSend(id: QueuedSendId) = Unit
    override fun sendQueuedNow(id: QueuedSendId) = Unit
    override fun resumeSendQueue() = Unit
    override fun toggleRunCollapsed(runId: ChatRunId) = Unit
    override fun toggleReasoningExpanded(messageId: ChatMessageId) = Unit
    override fun loadOlderMessages() = Unit
    override fun releaseOlderMessages() = Unit
    override fun expandTruncatedToolResult(messageId: ChatMessageId) = Unit
    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) = Unit
    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) = Unit
}
