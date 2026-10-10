package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/*
 * letta-mobile-y5q9z: the canvas mode's agent bubble, in the manner of Android's conversation
 * bubbles. Collapsed, the agent's head and its latest-message bubble float on the canvas; tapped,
 * the bubble opens into a card anchored to the head with the recent exchange and the prompt; its
 * "+" swaps the exchange for the agent's recent interactions, to hop to another conversation or
 * start a new thread. This is the state those three views move between.
 */

/**
 * Where the bubble is: collapsed or [expanded], and whether the expanded card shows the recent
 * interactions ([recentsOpen]) instead of the exchange. The recents only show on an expanded card.
 *
 * [conversationId] is the conversation the bubble last saw: a hop (or a new thread) lands on
 * another one, and the card goes back from the recents to the exchange there.
 */
@Stable
internal class CanvasBubbleState(
    expanded: Boolean = false,
    recentsOpen: Boolean = false,
    conversationId: String? = null,
) {
    var expanded: Boolean by mutableStateOf(expanded)
        private set

    var recentsOpen: Boolean by mutableStateOf(recentsOpen && expanded)
        private set

    var conversationId: String? = conversationId
        private set

    fun expand() {
        expanded = true
    }

    /** Back to the head and its bubble; the recents close with the card. */
    fun collapse() {
        expanded = false
        recentsOpen = false
    }

    fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    /** The "+": opens the recents (expanding the card first if it was collapsed), or closes them. */
    fun toggleRecents() {
        if (recentsOpen) {
            recentsOpen = false
        } else {
            expanded = true
            recentsOpen = true
        }
    }

    fun closeRecents() {
        recentsOpen = false
    }

    /** Back: the recents close first, then the card folds into the head. */
    fun back() {
        if (recentsOpen) closeRecents() else collapse()
    }

    /**
     * The page now shows [id]. Another conversation than the last one seen (a hop, a new thread)
     * closes the recents and keeps the card as it was; the first sighting changes nothing, so a
     * state restored after process death keeps its recents open.
     */
    fun onConversation(id: String?) {
        val previous = conversationId
        conversationId = id
        if (previous != null && previous != id) recentsOpen = false
    }

    companion object {
        /** Survives configuration changes and process death: the card comes back as it was left. */
        val Saver: Saver<CanvasBubbleState, String> = Saver(
            // "expanded:recentsOpen:conversationId"; the id goes last, so a ':' inside it survives.
            save = { listOf(it.expanded, it.recentsOpen, it.conversationId.orEmpty()).joinToString(SEPARATOR) },
            restore = { saved ->
                val parts = saved.split(SEPARATOR, limit = SAVED_PARTS)
                CanvasBubbleState(
                    expanded = parts.getOrNull(0) == true.toString(),
                    recentsOpen = parts.getOrNull(1) == true.toString(),
                    conversationId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                )
            },
        )

        private const val SEPARATOR = ":"
        private const val SAVED_PARTS = 3
    }
}

/**
 * The page's bubble state. A hop from the bubble's recents opens the next conversation's page with
 * its card already open (on Android a hop is a new chat route, so the card would otherwise come back
 * collapsed): see [CanvasBubbleArrival].
 */
@Composable
internal fun rememberCanvasBubbleState(expanded: Boolean = false): CanvasBubbleState =
    rememberSaveable(saver = CanvasBubbleState.Saver) {
        CanvasBubbleState(expanded = expanded || CanvasBubbleArrival.take())
    }

/**
 * letta-mobile-y5q9z: carries "the card was open" across a hop. On Android each conversation is its
 * own chat route with its own page, so the bubble that hopped and the bubble that arrives share no
 * state; the hop [mark]s it here and the arriving page [take]s it once. Desktop keeps one page across
 * conversations, so its bubble [take]s the mark as soon as it sees the new conversation instead.
 * Main thread only, like the composition that reads it.
 */
internal object CanvasBubbleArrival {
    private var pending = false

    fun mark() {
        pending = true
    }

    /** Whether a hop is arriving, cleared so it applies once. */
    fun take(): Boolean = pending.also { pending = false }
}
