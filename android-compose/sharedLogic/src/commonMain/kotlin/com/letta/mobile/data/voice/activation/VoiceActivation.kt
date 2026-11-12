package com.letta.mobile.data.voice.activation

import com.letta.mobile.data.voice.VoiceError
import kotlinx.coroutines.flow.Flow

/** How the person started (or ended) a voice turn. */
enum class VoiceActivationKind {
    /** A button or key held while speaking. */
    PUSH_TO_TALK,

    /** A tap on the voice control. */
    TAP,

    /** A keyboard shortcut. */
    HOTKEY,

    /** A detected wake phrase. */
    WAKE_WORD,
}

/** What an activation source asks of the loop. */
sealed interface VoiceActivationEvent {
    /** The source that raised it. */
    val kind: VoiceActivationKind

    /** Start listening (or interrupt the reply being spoken). */
    data class Activated(override val kind: VoiceActivationKind) : VoiceActivationEvent

    /** The person finished their turn (a push-to-talk release, a second tap). */
    data class EndTurn(override val kind: VoiceActivationKind) : VoiceActivationEvent

    /** The person cancelled (Escape): discard the turn and stop. */
    data class Discard(override val kind: VoiceActivationKind) : VoiceActivationEvent

    /**
     * An optional helper (a wake-word or voice-activity asset) is missing or failed to load. The
     * loop records [error] and keeps working with the remaining activations (push-to-talk, tap).
     */
    data class Degraded(
        override val kind: VoiceActivationKind,
        /** Why the helper is unavailable. */
        val error: VoiceError,
    ) : VoiceActivationEvent
}

/** Anything that can start the loop: a button, a hotkey, a wake-word detector. */
interface VoiceActivationSource {
    /** The requests, while collected. */
    val events: Flow<VoiceActivationEvent>
}
