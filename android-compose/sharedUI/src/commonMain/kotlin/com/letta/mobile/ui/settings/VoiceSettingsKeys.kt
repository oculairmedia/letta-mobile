package com.letta.mobile.ui.settings

/** Row keys of the Voice settings, which also tag them for tests. */
object VoiceSettingsKeys {
    /** The section. */
    const val SECTION: String = "voice"

    /** The speech server URL field. */
    const val URL: String = "voice-url"

    /** The API key field (only where the platform keeps keys securely). */
    const val KEY: String = "voice-key"

    /** The consent toggle. */
    const val CONSENT: String = "voice-consent"

    /** Interrupting replies by speaking: a toggle, or a note where the microphone cancels its own echo. */
    const val INTERRUPT: String = "voice-interrupt"

    /** The Save button. */
    const val SAVE: String = "voice-save"

    /** The Test button. */
    const val TEST: String = "voice-test"

    /** The outcome of the last save or test. */
    const val STATUS: String = "voice-status"

    /** The pane that shows the section over the chat. */
    const val PANE: String = "voice-setup-pane"
}
