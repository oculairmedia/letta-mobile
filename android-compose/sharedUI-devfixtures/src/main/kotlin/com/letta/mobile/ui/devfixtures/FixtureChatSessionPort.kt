package com.letta.mobile.ui.devfixtures

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A [ChatSessionPort] over fixture state, with no server behind it. The draft follows typing, Send
 * appends the prompt to the timeline and starts a fake run that Stop ends, and attachments come and
 * go; every other action is a no-op. A snapshot never calls these; the phone playground does.
 */
class FixtureChatSessionPort(
    state: ChatUiState = PhoneFixtures.state,
    composer: ChatComposerUiState = PhoneFixtures.idleComposer,
) : ChatSessionPort {
    private val ui = MutableStateFlow(state)
    private val draft = MutableStateFlow(composer)
    private var sentCount = 0

    override val uiState: StateFlow<ChatUiState> = ui
    override val composer: StateFlow<ChatComposerUiState> = draft
    override val actions: ChatActions = Actions()

    private inner class Actions : ChatActions {
        override fun updateComposerText(text: String) = draft.update { it.copy(text = text) }

        override fun send() = sendText(draft.value.text)

        override fun sendText(text: String) {
            if (text.isBlank() && draft.value.attachments.isEmpty()) return
            sentCount += 1
            val prompt = UiMessage(
                id = "sent-$sentCount",
                role = "user",
                content = text,
                timestamp = "2026-10-01T10:30:00Z",
                attachments = draft.value.attachments.map { image ->
                    com.letta.mobile.data.model.UiImageAttachment(base64 = image.base64, mediaType = image.mediaType)
                },
            )
            ui.update { it.copy(messages = (it.messages + prompt).toPersistentList(), isStreaming = true, isAgentTyping = true) }
            draft.update { it.copy(text = "", attachments = kotlinx.collections.immutable.persistentListOf()) }
        }

        override fun attachImage(image: MessageContentPart.Image) =
            draft.update { it.copy(attachments = (it.attachments + image).toPersistentList()) }

        override fun removeAttachment(index: Int) = draft.update { current ->
            current.copy(attachments = current.attachments.filterIndexed { i, _ -> i != index }.toPersistentList())
        }

        override fun reportComposerError(message: String) = draft.update { it.copy(error = message) }

        override fun clearComposerError() = draft.update { it.copy(error = null) }

        override fun runComposerCommand(command: ChatComposerCommand) = Unit

        override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit

        override fun stopRun() = ui.update { it.copy(isStreaming = false, isAgentTyping = false) }

        override fun rerun(message: UiMessage) = Unit

        override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) = Unit

        override fun submitA2uiAction(action: A2uiAction) = Unit

        override fun dismissA2uiSurface(surfaceId: String) = Unit

        override fun markA2uiSnackbarShown(id: Long) = Unit

        override fun cancelQueuedSend(id: QueuedSendId) = Unit

        override fun sendQueuedNow(id: QueuedSendId) = Unit

        override fun resumeSendQueue() = Unit

        override fun toggleRunCollapsed(runId: String) = Unit

        override fun toggleReasoningExpanded(messageId: String) = Unit

        override fun loadOlderMessages() = Unit

        override fun releaseOlderMessages() = Unit

        override fun expandTruncatedToolResult(messageId: String) = Unit

        override fun retryLoad() = Unit

        override fun clearError() = Unit

        override fun setFontScale(scale: Float) = Unit

        override fun selectModel(handle: String, effort: ReasoningEffortChoice) = Unit

        override fun changeWorkingDirectory(path: String) = Unit

        override fun updateSearchQuery(query: String) = Unit

        override fun clearSearch() = Unit

        override fun refreshGoalStatus() = Unit

        override fun continueGoal() = Unit
    }
}
