package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A minimal in-memory owner: enough to prove the contract, not a presenter. */
internal class FakeChatSessionPort : ChatSessionPort {
    private val ui = MutableStateFlow(ChatUiState())
    private val composerState = MutableStateFlow(ChatComposerUiState(canSend = true))
    val sent = mutableListOf<String>()

    override val uiState: StateFlow<ChatUiState> = ui
    override val composer: StateFlow<ChatComposerUiState> = composerState

    /** Settable, as an owner whose support changes while the page is open. */
    val capabilityState = MutableStateFlow(ChatSurfaceCapabilities.Default)
    override val capabilities: StateFlow<ChatSurfaceCapabilities> = capabilityState

    override val actions: ChatActions = object : ChatActions {
        override fun updateComposerText(text: String) = composerState.update { it.copy(text = text) }

        override fun send() {
            val draft = composerState.value
            if (!draft.hasPayload) return
            sent += draft.text
            composerState.update { it.copy(text = "", attachments = persistentListOf()) }
        }

        override fun sendText(text: String) {
            sent += text
        }

        override fun attachImage(image: MessageContentPart.Image) =
            composerState.update { it.copy(attachments = (it.attachments + image).toPersistentList()) }

        override fun removeAttachment(index: Int) =
            composerState.update { it.copy(attachments = it.attachments.toPersistentList().removeAt(index)) }

        override fun reportComposerError(message: String) = composerState.update { it.copy(error = message) }

        override fun clearComposerError() = composerState.update { it.copy(error = null) }

        override fun runComposerCommand(command: ChatComposerCommand) = Unit

        override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit

        override fun stopRun() = Unit

        override fun rerun(message: UiMessage) = Unit

        override fun submitApproval(answer: ChatApprovalAnswer) = Unit

        override fun submitA2uiAction(action: A2uiAction) = Unit

        override fun dismissA2uiSurface(surfaceId: A2uiSurfaceId) = Unit

        override fun markA2uiSnackbarShown(id: A2uiSnackbarId) = Unit

        override fun cancelQueuedSend(id: QueuedSendId) = Unit

        override fun sendQueuedNow(id: QueuedSendId) = Unit

        override fun resumeSendQueue() = Unit

        override fun toggleRunCollapsed(runId: ChatRunId) = Unit

        override fun toggleReasoningExpanded(messageId: ChatMessageId) = Unit

        override fun loadOlderMessages() = Unit

        override fun releaseOlderMessages() = Unit

        override fun expandTruncatedToolResult(messageId: ChatMessageId) = Unit

        override fun retryLoad() = Unit

        override fun clearError() = Unit

        override fun setFontScale(scale: Float) = Unit

        override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) = Unit

        override fun changeWorkingDirectory(directory: ChatWorkingDirectory) = Unit

        override fun updateSearchQuery(query: String) = Unit

        override fun clearSearch() = Unit

        override fun refreshGoalStatus() = Unit

        override fun continueGoal() = Unit
    }
}
