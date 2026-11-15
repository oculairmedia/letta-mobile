package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.local.LocalSpeechAvailability

/** Which ear voice mode listens with: the person's setting, the same on every client. */
enum class VoiceListening {
    /** The device's own recogniser when it has one, else the speech server; the server also takes over when the recogniser cannot go on. */
    AUTOMATIC,

    /** Only the device's own recogniser; voice mode reports it unavailable where there is none. */
    LOCAL,

    /** Only the speech server. */
    SERVER,
}

/** The ear one session listens with, after the setting met the device. */
enum class ListeningRoute {
    /** The local recogniser, with the server ear as its fallback. */
    LOCAL_WITH_FALLBACK,

    /** The local recogniser only. */
    LOCAL_ONLY,

    /** The server ear. */
    SERVER,
}

/** Resolves the [VoiceListening] setting against what the device offers. */
object ListeningChoice {
    /**
     * The route for [setting] on a device whose recogniser is [availability]: Automatic uses the
     * local recogniser when there is one and the server otherwise; Local only always picks the local
     * recogniser (which then reports UNAVAILABLE where there is none); Server never uses it.
     */
    fun resolve(setting: VoiceListening, availability: LocalSpeechAvailability): ListeningRoute = when (setting) {
        VoiceListening.AUTOMATIC -> if (availability.available) ListeningRoute.LOCAL_WITH_FALLBACK else ListeningRoute.SERVER
        VoiceListening.LOCAL -> ListeningRoute.LOCAL_ONLY
        VoiceListening.SERVER -> ListeningRoute.SERVER
    }
}
