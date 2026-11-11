package com.letta.mobile.data.voice

import kotlin.jvm.JvmInline

/** Which engine hears the person. */
enum class VoiceEarKind {
    /** The platform's own speech recogniser. */
    PLATFORM_RECOGNIZER,

    /** Captured audio sent to an OpenAI-compatible transcription endpoint. */
    TRANSCRIPTION_ENDPOINT,

    /** A local realtime model that hears audio directly and decides end of turn itself. */
    REALTIME_MODEL,
}

/** Which engine speaks replies. */
enum class VoiceMouthKind {
    /** The platform's own text-to-speech. */
    PLATFORM_TTS,

    /** An OpenAI-compatible speech endpoint. */
    SPEECH_ENDPOINT,
}

/** Who answers an utterance. */
enum class VoiceResponderKind {
    /** The conversation's agent answers directly. */
    CHAT_AGENT,

    /** A fast responder answers at once and delegates to the agent, whose answer arrives later. */
    FAST_WITH_AGENT_DELEGATION,
}

/** Whether the loop keeps listening after a reply. */
enum class VoiceConversationMode {
    /** One utterance and its reply, then idle. */
    SINGLE_SHOT,

    /** Back to listening after every reply until the person stops. */
    CONTINUOUS,
}

/** What happens to a reply that arrives after the loop went back to listening. */
enum class LateReplyPolicy {
    /** Speak it as soon as the person is not mid-utterance; hold it until then. */
    SPEAK_WHEN_QUIET,

    /** Never speak it (it still lands in the conversation). */
    DISCARD,
}

/** The base URL of an OpenAI-compatible speech server, without the `/v1/...` path. */
@JvmInline
value class SpeechEndpointUrl(
    /** The URL, for example `http://127.0.0.1:8880`. Never put credentials in it. */
    val value: String,
)

/** A model name on a speech endpoint. */
@JvmInline
value class SpeechModelName(
    /** The name the endpoint expects. */
    val value: String,
)

/** A voice name on a speech endpoint. */
@JvmInline
value class SpeechVoiceName(
    /** The name the endpoint expects. */
    val value: String,
)

/** An ISO-639 language hint, for example `en`. */
@JvmInline
value class SpeechLanguage(
    /** The language code. */
    val value: String,
)

/**
 * Where the endpoint engines send audio and text. The API key is not here: it lives in the secure
 * store, and destination consent lives in [com.letta.mobile.data.voice.consent.VoiceEndpointGrants], each in its own stored value.
 */
data class VoiceEndpointSettings(
    /** The speech server; null until the person configures one. */
    val baseUrl: SpeechEndpointUrl? = null,
    /** The transcription model. */
    val transcriptionModel: SpeechModelName = SpeechModelName("whisper-1"),
    /** The speech model. */
    val speechModel: SpeechModelName = SpeechModelName("tts-1"),
    /** The voice. */
    val voice: SpeechVoiceName = SpeechVoiceName("alloy"),
    /** A language hint for transcription; null lets the server detect it. */
    val language: SpeechLanguage? = null,
)

/** The wake word. Off by default; detection always runs on the device. */
data class VoiceWakeWordSettings(
    /** Whether the wake word starts the loop. Assets are downloaded only after this is turned on. */
    val enabled: Boolean = false,
    /** The phrases that start it, at most [MAX_PHRASES]. */
    val phrases: List<WakePhrase> = listOf(WakePhrase("hey letta")),
    /** The engine to try first; null takes the first registered engine that can run. */
    val preferredEngine: WakeWordEngineId? = null,
) {
    init {
        require(phrases.size <= MAX_PHRASES) { "Too many wake phrases" }
    }

    /** Bounds. */
    companion object {
        /** The most wake phrases a person can configure. */
        const val MAX_PHRASES: Int = 8
    }
}

/** The person's voice choices. Types only; the Settings page binds them later. */
data class VoiceSettings(
    /** Which engine hears. */
    val ear: VoiceEarKind = VoiceEarKind.PLATFORM_RECOGNIZER,
    /** Which engine speaks. */
    val mouth: VoiceMouthKind = VoiceMouthKind.PLATFORM_TTS,
    /** Who answers. */
    val responder: VoiceResponderKind = VoiceResponderKind.CHAT_AGENT,
    /** Single shot or continuous conversation. */
    val mode: VoiceConversationMode = VoiceConversationMode.CONTINUOUS,
    /** Whether speaking over a reply interrupts it. */
    val bargeIn: Boolean = true,
    /** What to do with replies that arrive late. */
    val lateReplies: LateReplyPolicy = LateReplyPolicy.SPEAK_WHEN_QUIET,
    /** The endpoint engines' destination and names. */
    val endpoint: VoiceEndpointSettings = VoiceEndpointSettings(),
    /** The wake word. */
    val wakeWord: VoiceWakeWordSettings = VoiceWakeWordSettings(),
)
