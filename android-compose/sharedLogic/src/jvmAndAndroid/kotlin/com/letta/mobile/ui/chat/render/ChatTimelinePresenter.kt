package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatStreamingPresencePolicy
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.Timeline
import kotlinx.collections.immutable.ImmutableList

/**
 * Platform-supplied stream signals the presence policy needs but that only the
 * host (ViewModel / controller) knows. Computed per emission, typically from the
 * projected message list (e.g. whether an A2UI response has landed).
 */
data class ChatPresenceSignals(
    val replyStreaming: Boolean,
    val clientModeStreamInFlight: Boolean,
    val a2uiThinkingActive: Boolean,
    val duplicateInitialMessageInFlight: Boolean,
)

/**
 * Platform-neutral result of presenting a [Timeline]: the ordered messages, the
 * cheap list-change hint, the derived streaming/typing flags, and the extracted
 * A2UI history. Each platform maps this onto its own UI state.
 */
data class ChatPresentation(
    val messages: ImmutableList<UiMessage>,
    val messageListChange: ChatMessageListChange,
    val isStreaming: Boolean,
    val isAgentTyping: Boolean,
    val a2uiMessages: List<A2uiMessage>,
    val anyConfirmed: Boolean,
    val tailIsAssistant: Boolean,
)

/**
 * Shared presentation core for the chat timeline: composes the stateful
 * [ChatTimelineProjector] (Timeline → messages, incremental tail cache, A2UI
 * extraction, list-change) with [ChatStreamingPresencePolicy] (streaming/typing
 * derivation) so Android and desktop produce identical presentation from the
 * same timeline.
 *
 * [project] and [present] are split so the host can keep the (heavier)
 * projection on a background dispatcher while computing its platform stream
 * signals — which may inspect the projected list and have side effects — on its
 * own coroutine, then assemble the neutral [ChatPresentation]. The host checks
 * [TimelineProjection.noChange] off [project] before doing any of that.
 */
class ChatTimelinePresenter {
    private val projector = ChatTimelineProjector()

    fun reset() = projector.reset()

    fun olderPrefixFor(conversationId: String): List<UiMessage> =
        projector.olderPrefixFor(conversationId)

    fun mergeOlderPage(
        conversationId: String,
        olderMessages: List<UiMessage>,
        existingMessages: List<UiMessage>,
    ): List<UiMessage> = projector.mergeOlderPage(conversationId, olderMessages, existingMessages)

    /** Project the timeline into the cached [TimelineProjection]. */
    fun project(
        timeline: Timeline,
        prefix: List<UiMessage>,
        previousState: ChatUiState,
    ): TimelineProjection = projector.project(timeline, prefix, previousState)

    /** Assemble the neutral presentation from a (changed) projection + signals. */
    fun present(
        projection: TimelineProjection,
        signals: ChatPresenceSignals,
        previousIsStreaming: Boolean,
        previousIsAgentTyping: Boolean,
    ): ChatPresentation {
        val presence = ChatStreamingPresencePolicy.derive(
            previousIsStreaming = previousIsStreaming,
            previousIsAgentTyping = previousIsAgentTyping,
            anyServerLocalPending = projection.anyLettaServerLocalPending,
            tailIsAssistant = projection.tailIsAssistant,
            replyStreaming = signals.replyStreaming,
            clientModeStreamInFlight = signals.clientModeStreamInFlight,
            a2uiThinkingActive = signals.a2uiThinkingActive,
            duplicateInitialMessageInFlight = signals.duplicateInitialMessageInFlight,
        )
        return ChatPresentation(
            messages = projection.ui,
            messageListChange = projection.messageListChange,
            isStreaming = presence.isStreaming,
            isAgentTyping = presence.isAgentTyping,
            a2uiMessages = projection.a2uiMessages,
            anyConfirmed = projection.anyConfirmed,
            tailIsAssistant = projection.tailIsAssistant,
        )
    }
}
