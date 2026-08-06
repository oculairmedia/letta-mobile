package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatStreamInputs
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
    // letta-mobile-c4igq.7: true while a chat turn is in flight on the transport
    // (spans all tool rounds until the real terminal). Holds presence across
    // inter-round gaps so a multi-tool turn does not flicker / look finished.
    val turnInFlight: Boolean = false,
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

    /** See [ChatTimelineProjector.releaseOlderPrefix]. */
    fun releaseOlderPrefix(
        conversationId: String,
        currentMessages: List<UiMessage>,
    ): List<UiMessage> = projector.releaseOlderPrefix(conversationId, currentMessages)

    /** Project the timeline into the cached [TimelineProjection]. */
    fun project(
        timeline: Timeline,
        prefix: List<UiMessage>,
        previousState: ChatUiState,
        isActiveRunStreaming: Boolean,
    ): TimelineProjection = projector.project(timeline, prefix, previousState, isActiveRunStreaming)

    /** Assemble the neutral presentation from a (changed) projection + signals. */
    fun present(
        projection: TimelineProjection,
        signals: ChatPresenceSignals,
        previousIsStreaming: Boolean,
        previousIsAgentTyping: Boolean,
    ): ChatPresentation {
        // letta-mobile-dir4k: feed the projection's "run is still active" flag
        // into the presence policy so a stale `signals.turnInFlight` cannot
        // keep presence stuck after the projection has folded. The transport's
        // ActiveTurn entry can linger one cycle after the projection settles
        // (e.g. the engine's `emitTurnFrame` for the terminal has not run
        // yet) — without this mask the composer kept showing "Thinking…" +
        // the red cancel button while the run disclosure correctly showed
        // "Worked for 5.0s".
        val presence = ChatStreamingPresencePolicy.derive(
            inputs = ChatStreamInputs(
                previousIsStreaming = previousIsStreaming,
                previousIsAgentTyping = previousIsAgentTyping,
                anyServerLocalPending = projection.anyLettaServerLocalPending,
                tailIsAssistant = projection.tailIsAssistant,
                replyStreaming = signals.replyStreaming,
                clientModeStreamInFlight = signals.clientModeStreamInFlight,
                a2uiThinkingActive = signals.a2uiThinkingActive,
                duplicateInitialMessageInFlight = signals.duplicateInitialMessageInFlight,
                turnInFlight = signals.turnInFlight,
                // letta-mobile-dir4k fix-forward: read the projection-layer
                // "is any run still active" fact (added to TimelineProjection
                // as `anyRunActive`) rather than the implementation-side
                // Local-pending count. Same boolean value today, but the name
                // is correct and the brief's regression test (which wires
                // both mask inputs from their REAL production sources and
                // flips each independently) can prove the mask is actually a
                // defence rather than a structural no-op hiding behind
                // aliasing.
                projectionRunActive = projection.anyRunActive,
            )
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
