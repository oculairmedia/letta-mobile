package com.letta.mobile.data.voice.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shared speech setup as any device may read it. It names the server and its models but never
 * the key: [hasApiKey] says only whether one is stored. Listening and speaking are reported apart,
 * because a device that listens on its own recogniser uses the host only to speak.
 */
@Serializable
data class HostVoiceSetupStatus(
    /** The contract version the host answered with. */
    @SerialName("contract_version") val contractVersion: Int,
    /** Whether a speech server is set up. */
    @SerialName("configured") val configured: Boolean,
    /** Opaque version of the stored setup, changed by every write; null when nothing is stored. */
    @SerialName("revision") val revision: String? = null,
    /** The speech server's base URL; null when nothing is set up. */
    @SerialName("base_url") val baseUrl: String? = null,
    /** Whether the host keeps a key for the speech server. */
    @SerialName("has_api_key") val hasApiKey: Boolean = false,
    /** Listening through the host. */
    @SerialName("transcription") val transcription: HostTranscriptionSetup = HostTranscriptionSetup(),
    /** Speaking through the host. */
    @SerialName("speech") val speech: HostSpeechSetup = HostSpeechSetup(),
    /** The shared grants for the server's origin. */
    @SerialName("consent") val consent: HostVoiceConsent = HostVoiceConsent(),
    /** What the host last learned about whether the server answers. */
    @SerialName("reachability") val reachability: HostVoiceReachability = HostVoiceReachability(),
)

/** Listening through the host: whether it is usable now, and with which model and language hint. */
@Serializable
data class HostTranscriptionSetup(
    /** True when the host would accept a transcription now: set up, with a model and an audio grant. */
    @SerialName("available") val available: Boolean = false,
    /** The transcription model. */
    @SerialName("model") val model: String? = null,
    /** The stored language hint; null lets the server detect it. */
    @SerialName("language") val language: String? = null,
)

/** Speaking through the host: whether it is usable now, and with which model and voice. */
@Serializable
data class HostSpeechSetup(
    /** True when the host would accept a synthesis now: set up, with model, voice and a text grant. */
    @SerialName("available") val available: Boolean = false,
    /** The speech model. */
    @SerialName("model") val model: String? = null,
    /** The voice. */
    @SerialName("voice") val voice: String? = null,
)

/** Which kinds of payload may go to the speech server. */
@Serializable
data class HostVoiceGrants(
    /** Microphone audio, sent for transcription. */
    @SerialName("audio_for_transcription") val audioForTranscription: Boolean = false,
    /** Reply text, sent for synthesis. */
    @SerialName("text_for_synthesis") val textForSynthesis: Boolean = false,
)

/** The shared grant record: what may be sent to which origin, and when it was granted. */
@Serializable
data class HostVoiceConsent(
    /** The origin the grants name (scheme, host and port); null when nothing is granted. */
    @SerialName("origin") val origin: String? = null,
    /** The granted payloads. */
    @SerialName("grants") val grants: HostVoiceGrants = HostVoiceGrants(),
    /** When the grants were last changed, in epoch milliseconds by the host's clock. */
    @SerialName("granted_at_ms") val grantedAtMs: Long? = null,
)

/** The outcome of the host's last check of the stored speech server. */
@Serializable
enum class HostVoiceReachabilityState {
    /** Not checked since the setup last changed. */
    @SerialName("unknown")
    UNKNOWN,

    /** The server answered. */
    @SerialName("reachable")
    REACHABLE,

    /** The check failed; the reachability's error says why. */
    @SerialName("failed")
    FAILED,
}

/** What the host last learned about the stored speech server, and when. */
@Serializable
data class HostVoiceReachability(
    /** The outcome. */
    @SerialName("state") val state: HostVoiceReachabilityState = HostVoiceReachabilityState.UNKNOWN,
    /** When it was checked, in epoch milliseconds by the host's clock; null when never. */
    @SerialName("checked_at_ms") val checkedAtMs: Long? = null,
    /** Why the check failed; null unless the state is failed. */
    @SerialName("error") val error: HostVoiceErrorCode? = null,
)

/** A successful connection check: the server answered and listed this many models. */
@Serializable
data class HostVoiceTestResult(
    /** How many models the server listed. */
    @SerialName("model_count") val modelCount: Int,
    /** When the check ran, in epoch milliseconds by the host's clock. */
    @SerialName("checked_at_ms") val checkedAtMs: Long,
)

/** One model or voice the speech server offers. */
@Serializable
data class HostVoiceCatalogEntry(
    /** The name the server expects. */
    @SerialName("id") val id: String,
)

/** Where a voice list came from. */
@Serializable
enum class HostVoiceListSource {
    /** The speech server listed its voices. */
    @SerialName("upstream")
    UPSTREAM,

    /** The server lists none, so only the stored voice is offered. */
    @SerialName("configured")
    CONFIGURED,
}

/** The models and voices of the stored speech server. */
@Serializable
data class HostVoiceModels(
    /** The models, as the server lists them. */
    @SerialName("models") val models: List<HostVoiceCatalogEntry>,
    /** The voices. */
    @SerialName("voices") val voices: List<HostVoiceCatalogEntry>,
    /** Where [voices] came from. */
    @SerialName("voices_source") val voicesSource: HostVoiceListSource,
    /** When the server was asked, in epoch milliseconds by the host's clock. */
    @SerialName("checked_at_ms") val checkedAtMs: Long,
)
