package com.letta.mobile.ui.devfixtures

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import java.lang.reflect.Proxy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A [ChatSessionPort] over fixture state, with no server behind it. The draft follows typing, Send
 * appends the prompt to the timeline and starts a fake run that Stop ends, and images can be
 * attached; every other action does nothing. A snapshot never calls these; the phone playground does.
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

    /** The prompt the current draft makes, numbered so each send is its own message. */
    private fun promptFromDraft(): UiMessage {
        sentCount += 1
        val current = draft.value
        return UiMessage(
            id = "sent-$sentCount",
            role = "user",
            content = current.text,
            timestamp = "2026-10-01T10:30:00Z",
            attachments = current.attachments.map { image -> UiImageAttachment(base64 = image.base64, mediaType = image.mediaType) },
        )
    }

    private inner class Actions : ChatActions by IgnoredChatActions {
        override fun updateComposerText(text: String) = draft.update { it.copy(text = text) }

        override fun send() {
            if (!draft.value.hasPayload) return
            val prompt = promptFromDraft()
            ui.update { it.copy(messages = (it.messages + prompt).toPersistentList(), isStreaming = true, isAgentTyping = true) }
            draft.update { it.copy(text = "", attachments = persistentListOf()) }
        }

        override fun attachImage(image: MessageContentPart.Image) =
            draft.update { it.copy(attachments = (it.attachments + image).toPersistentList()) }

        override fun stopRun() = ui.update { it.copy(isStreaming = false, isAgentTyping = false) }
    }
}

/**
 * Every [ChatActions] call accepted and ignored: what a fixture does with the actions it does not act
 * on (approvals, A2UI, queue, timeline toggles, search, goals). Built as a proxy so a new action on
 * the interface needs no change here.
 */
private val IgnoredChatActions: ChatActions = Proxy.newProxyInstance(
    ChatActions::class.java.classLoader,
    arrayOf(ChatActions::class.java),
) { proxy, method, args ->
    when (method.name) {
        "equals" -> proxy === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "IgnoredChatActions"
        else -> null
    }
} as ChatActions
