package com.letta.mobile.ui.chat.voice

/** Test tags of the voice mode controls. */
object VoiceModeTags {
    /** The chat bar's voice mode button. */
    const val BUTTON: String = "voice-mode-button"

    /** Voice mode's notice above the prompt (a failure, a missing setup or permission). */
    const val STRIP: String = "voice-mode-strip"

    /** The notice's action (Retry, Set up). */
    const val STRIP_ACTION: String = "voice-mode-strip-action"

    /** The notice's dismiss, which turns voice mode off. */
    const val STRIP_CLOSE: String = "voice-mode-strip-close"

    /** The notice that listening moved from the device's recogniser to the speech server. */
    const val HANDOVER: String = "voice-mode-handover"

    /** That notice's close, which keeps voice mode on. */
    const val HANDOVER_CLOSE: String = "voice-mode-handover-close"

    /** The voice card docked above the prompt while voice mode runs. */
    const val CARD: String = "voice-card"

    /** The card's speaker name ("You", or the agent's name). */
    const val CARD_SPEAKER: String = "voice-card-speaker"

    /** The card's timer: how long the current speaker has had the turn. */
    const val CARD_TIMER: String = "voice-card-timer"

    /** The card's body: the person's words, the status, the error or the reply. */
    const val CARD_BODY: String = "voice-card-body"

    /** The card's microphone mute toggle. */
    const val CARD_MIC: String = "voice-card-mic"

    /** The card's speaker mute toggle. */
    const val CARD_SPEAKER_MUTE: String = "voice-card-speaker-mute"

    /** The card's speaking speed menu button. */
    const val CARD_SPEED: String = "voice-card-speed"

    /** The card's fold / unfold button. */
    const val CARD_COLLAPSE: String = "voice-card-collapse"

    /** The card's End button, which turns voice mode off. */
    const val CARD_END: String = "voice-card-end"

    /** The card's action after a failed utterance (Retry, Set up). */
    const val CARD_ACTION: String = "voice-card-action"
}
