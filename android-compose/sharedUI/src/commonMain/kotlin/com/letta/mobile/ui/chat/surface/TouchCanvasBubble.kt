package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.data.chat.branch.PendingComposerDrafts
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.chat.surface.recents.RecentInteractionsActions

/*
 * letta-mobile-y5q9z: the page's side of the canvas bubble. The bubble's card shows what the docked
 * panel shows (the recent exchange, the page's own composer), so the draft, the send and the run are
 * the page's; its "+" hops through the host's own "open conversation" and "new chat".
 */

/** The Touch dock's parts that outlive a mode switch: the bar's metrics and, over a canvas, the bubble. */
@Immutable
internal class TouchDockParts(val bar: TouchBarMetrics, val bubble: CanvasBubbleState?)

/**
 * The canvas bubble for [frame]: [state], the exchange and the composer the docked panel would
 * show, Stop while the turn is [busy], and the recent interactions. Seeing another conversation
 * (a hop landing) closes the recents; Back folds the card.
 */
@Composable
internal fun rememberTouchBubble(frame: ChatSurfaceFrame, state: CanvasBubbleState, busy: Boolean): TouchBubble {
    ObserveBubbleConversation(frame, state)
    CanvasBubbleBackHandler(enabled = state.expanded && frame.mode == ChatSurfaceMode.Docked, onBack = state::back)
    val recents = frame.recents
    return TouchBubble(
        state = state,
        exchange = { modifier -> DockedReplyCard(dockedReplyParams(frame), modifier) },
        composer = { DockComposer(frame, ChatSurfaceMode.Docked, collapsed = true) },
        recents = recents,
        onStop = if (busy) frame.port.actions::stopRun else null,
        hop = recents?.let { recentsHop(frame, it, state::closeRecents) },
    )
}

/** Tells [state] which conversation the page shows, so a hop landing closes the recents. */
@Composable
internal fun ObserveBubbleConversation(frame: ChatSurfaceFrame, state: CanvasBubbleState) {
    val conversationId = frame.conversationId()
    LaunchedEffect(state, conversationId) {
        // A hop marked for a page that never came (desktop keeps this page) is spent here.
        CanvasBubbleArrival.take()
        state.onConversation(conversationId)
    }
}

/**
 * The docked panel's recent interactions (desktop): the same list and hop as the phone's bubble,
 * opened by the "+" in the panel's header. Null when the host offers no conversations.
 */
@Composable
internal fun rememberDockedRecents(frame: ChatSurfaceFrame): DockedRecents? {
    val state = rememberCanvasBubbleState(expanded = true)
    ObserveBubbleConversation(frame, state)
    val recents = frame.recents ?: return null
    return DockedRecents(state, recents, recentsHop(frame, recents, state::closeRecents))
}

/** The docked panel's "+": its state (only [CanvasBubbleState.recentsOpen] matters there), rows and hop. */
@Immutable
internal class DockedRecents(
    val state: CanvasBubbleState,
    val recents: ChatRecentInteractions,
    val hop: RecentInteractionsActions,
)

/**
 * What picking a recent interaction does: the current conversation just closes the list; another one
 * (or a new thread) leaves the unsent draft for this conversation's next visit, marks the card to
 * open on arrival, and hands over to the host's own navigation.
 */
internal fun recentsHop(frame: ChatSurfaceFrame, recents: ChatRecentInteractions, closeList: () -> Unit): RecentInteractionsActions {
    val current = frame.conversationId()
    val draft = frame.composer.text
    val leave: () -> Unit = {
        if (current != null && draft.isNotBlank()) PendingComposerDrafts.shared.put(current, draft)
        CanvasBubbleArrival.mark()
        closeList()
    }
    return RecentInteractionsActions(
        openConversation = { id ->
            if (id == current) {
                closeList()
            } else {
                leave()
                recents.onOpenConversation(id)
            }
        },
        newThread = {
            leave()
            recents.onNewThread()
        },
    )
}

/** The conversation on the page, once it is known. */
internal fun ChatSurfaceFrame.conversationId(): String? = (uiState.conversationState as? ConversationState.Ready)?.conversationId
