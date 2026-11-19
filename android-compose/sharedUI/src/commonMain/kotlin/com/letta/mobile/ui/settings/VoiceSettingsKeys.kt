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

    /** Which ear voice mode listens with: Automatic, this device only, or the speech server. */
    const val LISTENING: String = "voice-listening"

    /** The Save button. */
    const val SAVE: String = "voice-save"

    /** The Test button. */
    const val TEST: String = "voice-test"

    /** The outcome of the last save or test. */
    const val STATUS: String = "voice-status"

    /** This device's own server's transcription model picker. */
    const val TRANSCRIPTION_MODEL: String = "voice-transcription-model"

    /** This device's own server's speech model picker. */
    const val SPEECH_MODEL: String = "voice-speech-model"

    /** This device's own server's voice picker. */
    const val VOICE: String = "voice-voice"

    /** Appended to a model or voice picker's key: its custom-name field. */
    const val CUSTOM_SUFFIX: String = "-custom"

    /** Appended to the first picker's key: the server list's loading, failure or not-yet-saved note. */
    const val CATALOG_SUFFIX: String = "-catalog"

    /** Appended to the first picker's key: listing the server again after a failure. */
    const val CATALOG_RETRY_SUFFIX: String = "-catalog-retry"

    /** The pane that shows the section over the chat. */
    const val PANE: String = "voice-setup-pane"

    /** The note naming the host's shared speech server. */
    const val HOST_NOTE: String = "voice-host-note"

    /** The shared server's address, as the host reports it. */
    const val HOST_SERVER: String = "voice-host-server"

    /** Whether the host keeps a key for the shared server (never the key). */
    const val HOST_KEY: String = "voice-host-key"

    /** The shared listening model. */
    const val HOST_LISTENING: String = "voice-host-listening"

    /** The shared speech model and voice. */
    const val HOST_SPEAKING: String = "voice-host-speaking"

    /** Whether the shared server answered when last checked. */
    const val HOST_REACHABILITY: String = "voice-host-reachability"

    /** The note that the shared setup lacks a model, a voice or a grant. */
    const val HOST_INCOMPLETE: String = "voice-host-incomplete"

    /** The note shown to a device that may not change the shared setup. */
    const val HOST_READ_ONLY: String = "voice-host-read-only"

    /** This device's acknowledgement that its recordings go to the shared server. */
    const val HOST_ACK: String = "voice-host-ack"

    /** Using this device's own server instead of the shared one. */
    const val DEVICE_ONLY: String = "voice-device-only"

    /** The admin's shared server URL field. */
    const val HOST_URL: String = "voice-host-url"

    /** The admin's write-only key field for the shared server. */
    const val HOST_KEY_FIELD: String = "voice-host-key-field"

    /** The shared transcription model field. */
    const val HOST_TRANSCRIPTION_MODEL: String = "voice-host-transcription-model"

    /** The shared speech model field. */
    const val HOST_SPEECH_MODEL: String = "voice-host-speech-model"

    /** The shared voice field. */
    const val HOST_VOICE: String = "voice-host-voice"

    /** The shared grant for recordings. */
    const val HOST_GRANT_AUDIO: String = "voice-host-grant-audio"

    /** The shared grant for replies. */
    const val HOST_GRANT_TEXT: String = "voice-host-grant-text"

    /** Saving the shared setup. */
    const val HOST_SAVE: String = "voice-host-save"

    /** Checking the shared server. */
    const val HOST_TEST: String = "voice-host-test"

    /** Removing the shared setup. */
    const val HOST_CLEAR: String = "voice-host-clear"

    /** The outcome of the last shared-setup save or check. */
    const val HOST_STATUS: String = "voice-host-status"

    /** Sharing this device's own server with every device (or the note that an admin can). */
    const val SHARE: String = "voice-share"

    /** Sharing this device's own server without its key. */
    const val SHARE_WITHOUT_KEY: String = "voice-share-without-key"

    /** The outcome of sharing. */
    const val SHARE_STATUS: String = "voice-share-status"

    /** Removing this device's own server once the shared one is in force. */
    const val REMOVE_LOCAL: String = "voice-remove-local"
}
