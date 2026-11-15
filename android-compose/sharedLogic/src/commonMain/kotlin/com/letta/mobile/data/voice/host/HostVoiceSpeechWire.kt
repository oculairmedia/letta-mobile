package com.letta.mobile.data.voice.host

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.endpoint.RecognizerConfidence
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `voice.transcribe` params: one clip, Base64 of its bytes. Prints its size only. */
@Serializable
internal data class HostTranscriptionParams(
    @SerialName("audio") val audio: String,
    @SerialName("encoding") val encoding: String = WAV,
    @SerialName("language") val language: String? = null,
) {
    override fun toString(): String = "HostTranscriptionParams(audioChars=${audio.length}, encoding=$encoding)"

    companion object {
        /** The only encoding v1 accepts: mono 16-bit PCM WAVE. */
        const val WAV: String = "wav"

        fun of(clip: AudioClip, language: String?): HostTranscriptionParams =
            HostTranscriptionParams(Base64.encode(clip.bytes), clip.encoding.wireName, language)
    }
}

/** The recogniser's numbers for one segment, never its text. */
@Serializable
internal data class HostTranscriptSegment(
    @SerialName("no_speech_prob") val noSpeechProbability: Double,
    @SerialName("avg_logprob") val averageLogProbability: Double,
)

/** Which response format the speech server answered in. */
@Serializable
internal enum class HostTranscriptFormat {
    @SerialName("verbose_json")
    VERBOSE_JSON,

    @SerialName("json")
    JSON,
}

/** `voice.transcribe` result. Prints no words. */
@Serializable
internal data class HostTranscriptionResult(
    @SerialName("text") val text: String,
    @SerialName("format") val format: HostTranscriptFormat,
    @SerialName("segments") val segments: List<HostTranscriptSegment> = emptyList(),
) {
    override fun toString(): String = "HostTranscriptionResult(textLength=${text.length}, format=$format, segments=${segments.size})"

    fun toTranscript(): SpeechTranscript = SpeechTranscript(
        text = VoiceText(text),
        segments = segments.map { RecognizerConfidence(it.noSpeechProbability, it.averageLogProbability) },
    )
}

/** `voice.speech` params. Prints no words. */
@Serializable
internal data class HostSpeechParams(
    @SerialName("text") val text: String,
) {
    override fun toString(): String = "HostSpeechParams(textLength=${text.length})"
}

/** What one streamed speech frame carries. */
@Serializable
internal enum class HostSpeechEvent {
    /** The PCM format of every audio frame after it; sent once, first. */
    @SerialName("format")
    FORMAT,

    /** A run of PCM samples. */
    @SerialName("audio")
    AUDIO,
}

/** The only sample format v1 streams: signed 16-bit little-endian, interleaved. */
internal const val HOST_SPEECH_SAMPLE_FORMAT: String = "s16le"

/** One `admin_rpc_stream` frame of a `voice.speech` exchange, before its final response. Prints no audio. */
@Serializable
internal data class HostSpeechStreamFrame(
    @SerialName("type") val type: String = HostVoiceWire.STREAM_FRAME_TYPE,
    @SerialName("request_id") val requestId: String,
    @SerialName("seq") val seq: Int,
    @SerialName("event") val event: HostSpeechEvent,
    @SerialName("sample_rate_hz") val sampleRateHz: Int? = null,
    @SerialName("channels") val channels: Int? = null,
    @SerialName("sample_format") val sampleFormat: String? = null,
    @SerialName("pcm") val pcm: String? = null,
) {
    override fun toString(): String = "HostSpeechStreamFrame(seq=$seq, event=$event, pcmChars=${pcm?.length ?: 0})"

    /** The format this frame announces; null unless it is a well-formed format frame. */
    fun format(): PcmFormat? {
        val rate = sampleRateHz?.takeIf { it > 0 }
        val count = channels?.takeIf { it > 0 }
        val valid = event == HostSpeechEvent.FORMAT && sampleFormat == HOST_SPEECH_SAMPLE_FORMAT
        return if (valid && rate != null && count != null) PcmFormat(rate, count) else null
    }

    /** This audio frame's samples in [format]; null unless it holds whole sample frames. */
    fun chunk(format: PcmFormat): PcmChunk? {
        val bytes = pcm?.takeIf { event == HostSpeechEvent.AUDIO }?.let { runCatching { Base64.decode(it) }.getOrNull() }
        val frameBytes = format.channels * BYTES_PER_SAMPLE
        if (bytes == null || bytes.isEmpty() || bytes.size % frameBytes != 0) return null
        val samples = ShortArray(bytes.size / BYTES_PER_SAMPLE) { index ->
            val low = bytes[index * BYTES_PER_SAMPLE].toInt() and BYTE_MASK
            val high = bytes[index * BYTES_PER_SAMPLE + 1].toInt()
            ((high shl BITS_PER_BYTE) or low).toShort()
        }
        return PcmChunk(samples, format)
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
    }
}

/** The final `voice.speech` result, after the last audio frame. */
@Serializable
internal data class HostSpeechSummary(
    @SerialName("audio_frames") val audioFrames: Int,
    @SerialName("sample_frames") val sampleFrames: Long,
)

/** The frame a client sends on a `voice.speech` stream to cancel it. */
@Serializable
internal data class HostSpeechCancelFrame(
    @SerialName("type") val type: String = HostVoiceWire.CANCEL_FRAME_TYPE,
    @SerialName("request_id") val requestId: String,
)
