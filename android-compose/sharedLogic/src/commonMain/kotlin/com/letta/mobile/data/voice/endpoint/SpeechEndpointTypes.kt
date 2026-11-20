package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.tone.ToneCue
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A bearer key for a speech endpoint. It never prints ([toString] is redacted), has no structural
 * equality, and is only read to build the Authorization header.
 */
class SpeechApiKey(private val secret: String) {
    init {
        require(secret.isNotBlank()) { "Speech API key is blank" }
    }

    /** The Authorization header value. */
    internal fun bearer(): String = "Bearer $secret"

    override fun toString(): String = "SpeechApiKey(redacted)"
}

/** Budgets for one speech request. */
data class SpeechTimeouts(
    /** Longest wait to connect. */
    val connect: Duration = 10.seconds,
    /** Longest wait for the whole request, including the response body. */
    val request: Duration = 60.seconds,
)

/** Where and how to reach an OpenAI-compatible speech server. */
class SpeechEndpointConfig(
    /** The server's base URL, without `/v1`. */
    val baseUrl: SpeechEndpointUrl,
    /** The bearer key; null for a server without auth. */
    val apiKey: SpeechApiKey? = null,
    /** Request budgets. */
    val timeouts: SpeechTimeouts = SpeechTimeouts(),
) {
    override fun toString(): String = "SpeechEndpointConfig(keySet=${apiKey != null}, timeouts=$timeouts)"
}

/** Which endpoint call. */
enum class SpeechOperation {
    /** `POST /v1/audio/transcriptions`. */
    TRANSCRIPTION,

    /** `POST /v1/audio/speech`. */
    SPEECH,

    /** `GET /v1/models`, only to learn whether the server answers; carries no audio or text. */
    REACHABILITY,
}

/**
 * A speech request failed. The message names only the operation and the fixed [code]; it never
 * carries the URL, the key, the request text or the response body.
 */
class SpeechEndpointException(
    /** The operation that failed. */
    val operation: SpeechOperation,
    /** Why: UNREACHABLE, UNAUTHORIZED, REJECTED, BAD_RESPONSE, TIMED_OUT, PERMISSION_REQUIRED or INSECURE_TRANSPORT. */
    val code: VoiceErrorCode,
    /** The HTTP status, when the server answered. */
    val status: Int? = null,
) : Exception("Speech endpoint $operation failed: $code")

/** A bounded diagnostic record of one request: operation, outcome and status only. */
data class SpeechClientEvent(
    /** The operation. */
    val operation: SpeechOperation,
    /** The failure code; null on success. */
    val failure: VoiceErrorCode?,
    /** The HTTP status, when the server answered. */
    val status: Int?,
)

/** Receives [SpeechClientEvent]s for telemetry. Never receives text, audio, URLs or keys. */
fun interface SpeechClientDiagnostics {
    /** Records one request's outcome. */
    fun record(event: SpeechClientEvent)
}

/** One transcription request. */
data class TranscriptionRequest(
    /** The audio to transcribe. */
    val audio: AudioClip,
    /** The model. */
    val model: SpeechModelName,
    /** A language hint; null lets the server detect it. */
    val language: SpeechLanguage? = null,
)

/** One synthesis request. */
data class SpeechRequest(
    /** The text to speak. */
    val text: VoiceText,
    /** The model. */
    val model: SpeechModelName,
    /** The voice. */
    val voice: SpeechVoiceName,
    /** The audio format to return. */
    val encoding: AudioEncoding = AudioEncoding.MP3,
    /** How fast to speak; sent as `speed` unless it is [SpeechSpeed.Normal]. */
    val speed: SpeechSpeed = SpeechSpeed.Normal,
    /** How to say it, sent as `instructions`; omitted when null. */
    val tone: ToneCue? = null,
)

/**
 * How fast a speech endpoint speaks, as the OpenAI `speed` multiplier, from [MIN] to [MAX];
 * [Normal] is the server's own pace.
 */
@JvmInline
value class SpeechSpeed(
    /** The multiplier. */
    val value: Float,
) {
    init {
        require(value in MIN..MAX) { "Speech speed out of range" }
    }

    /** Bounds and the default. */
    companion object {
        /** The slowest speed offered. */
        const val MIN: Float = 0.75f

        /** The fastest speed offered. */
        const val MAX: Float = 1.5f

        /** The server's own pace. */
        val Normal: SpeechSpeed = SpeechSpeed(1f)

        /** [value] clamped into range; a value that is not a number is [Normal]. */
        fun coerce(value: Float): SpeechSpeed = if (value.isNaN()) Normal else SpeechSpeed(value.coerceIn(MIN, MAX))
    }
}

/** What the recogniser itself reported about whether one segment is speech: numbers only, never text. */
data class RecognizerConfidence(
    /** The segment's `no_speech_prob`, 0..1: how sure the recogniser is nothing was said. */
    val noSpeechProbability: Double,
    /** The segment's `avg_logprob` (natural log; 0 is certain, lower is less sure). */
    val averageLogProbability: Double,
)

/** One transcription: the text, and the recogniser's own numbers for each segment when the server reported them. */
data class SpeechTranscript(
    /** The words heard. */
    val text: VoiceText,
    /** One entry per segment; empty when the server sent no usable segments (plain `json`, or `verbose_json` ignored). */
    val segments: List<RecognizerConfidence> = emptyList(),
)
