package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The speech server as the host's voice routes use it: an OpenAI-compatible server reached over HTTP.
 * This is the seam a speech engine plugs in behind; the routes never talk HTTP themselves. Every failure
 * is a [VoiceUpstreamFailure] with a code from the contract's closed set, never the server's own words,
 * its address or the key. Cancelling a call closes its request.
 */
internal interface VoiceSpeechUpstream {
    /** `GET {base}/v1/models`: the model ids the server lists. */
    suspend fun models(target: VoiceUpstreamTarget): List<String>

    /** `GET {base}/v1/audio/voices`: the voice ids, or null when the server does not list voices. */
    suspend fun voices(target: VoiceUpstreamTarget): List<String>?

    /** `POST {base}/v1/audio/transcriptions` with one WAVE clip. */
    suspend fun transcribe(target: VoiceUpstreamTarget, request: VoiceTranscriptionCall): VoiceTranscriptionAnswer

    /**
     * `POST {base}/v1/audio/speech` asking for WAVE, handing each run of the body to [onBytes] as it
     * arrives. A [VoiceUpstreamFailure] thrown by [onBytes] ends the call with its code.
     */
    suspend fun speech(target: VoiceUpstreamTarget, request: VoiceSpeechCall, onBytes: suspend (ByteArray) -> Unit)

    /** Releases the HTTP client. */
    fun close()
}

/** Where one upstream call goes and the key it carries, if any. Prints neither. */
internal class VoiceUpstreamTarget(
    val baseUrl: String,
    val apiKey: String?,
) {
    override fun toString(): String = "VoiceUpstreamTarget(keySet=${apiKey != null})"
}

/** One clip to transcribe. Prints no audio. */
internal class VoiceTranscriptionCall(
    val audio: ByteArray,
    val model: String,
    val language: String?,
    /** Whether to ask for `verbose_json` (with segment numbers) rather than plain `json`. */
    val verbose: Boolean,
) {
    override fun toString(): String = "VoiceTranscriptionCall(bytes=${audio.size}, verbose=$verbose)"
}

/** What a transcription call came back with. */
internal sealed interface VoiceTranscriptionAnswer {
    /** The transcript. */
    class Heard(val transcript: SpeechTranscript) : VoiceTranscriptionAnswer

    /** The server refused the asked-for response format (400, 415 or 422 to a `verbose_json` request). */
    data object FormatRefused : VoiceTranscriptionAnswer
}

/** One text to synthesise. Prints no words. */
internal class VoiceSpeechCall(
    val text: String,
    val model: String,
    val voice: String,
    /** The reply's tone cue for the speech server's `instructions`; null for none. */
    val instructions: String? = null,
) {
    override fun toString(): String = "VoiceSpeechCall(textLength=${text.length}, instructed=${instructions != null})"
}

/** An upstream call failed with [code]; [status] is the HTTP status when the server answered. */
internal class VoiceUpstreamFailure(
    val code: HostVoiceErrorCode,
    val status: Int? = null,
) : Exception(code.wire)

/** The contract's budgets for calls to the speech server. */
internal data class VoiceUpstreamTimeouts(
    val connect: Duration = 10.seconds,
    val firstByte: Duration = 15.seconds,
    val transcription: Duration = 30.seconds,
    val synthesis: Duration = 120.seconds,
    val reachability: Duration = 10.seconds,
)
