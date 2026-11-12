package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
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
)
