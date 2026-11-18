package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The voice card's plain values. The card itself is drawn in the composer (with the theme and the
 * shared components); what it is drawn from lives here, beside the handle and the feed.
 */

/** Whose turn the voice card shows. */
internal enum class VoiceCardSpeaker {
    /** The person: voice mode is listening to them, hearing them, or listening on after a failed utterance. */
    You,

    /** The agent: voice mode waits for its reply or speaks it. */
    Agent,
}

/** What the voice card shows of the conversation: the agent's [name], and the [reply] it speaks while no pieces are published. */
@Immutable
internal data class VoiceCardConversation(val name: String, val reply: String?)

/**
 * The header's turn: whose it is, their [name] and [tint], what voice mode is doing ([status]:
 * "Listening", "Thinking", "Speaking"), whether the agent is still [thinking] or [speaking], and how
 * long the turn has run.
 */
@Immutable
internal class VoiceCardTurn(
    val speaker: VoiceCardSpeaker,
    val name: String,
    val tint: Color,
    val status: String,
    val thinking: Boolean,
    val speaking: Boolean,
    val elapsedMs: () -> Long,
)

/** A mute switch's test [tag], its [label] ("Mute microphone"), and its [glyph] and [mutedGlyph]. */
@Immutable
internal class VoiceCardMuteLook(val tag: String, val label: String, val glyph: ImageVector, val mutedGlyph: ImageVector)

/**
 * The card's clock: [now] on the feed's clock (read at draw time and in derived state, never directly
 * in composition) and when the current speaker's turn started ([turnStartMs]), which also tells the
 * current reply's spoken pieces from an earlier reply's.
 */
@Immutable
internal class VoiceCardTiming(val now: State<Long>, val turnStartMs: Long)
