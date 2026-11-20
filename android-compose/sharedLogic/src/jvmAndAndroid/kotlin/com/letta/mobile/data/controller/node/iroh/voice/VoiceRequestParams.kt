package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.audio.pcm.WavParse
import com.letta.mobile.data.voice.host.HostTranscriptionParams
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.tone.ToneCues
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject

/** Decodes and validates `voice.transcribe` and `voice.speech` params; refusals are closed codes and echo nothing. */
internal object VoiceRequestParams {
    /**
     * `voice.transcribe` params as a clip: only `wav`, mono 16-bit PCM, at most 1 MiB decoded and 30 s by
     * its header. The size is checked before the clip is decoded.
     */
    fun clip(params: JsonObject?): VoiceClip {
        val fields = params ?: voiceInvalid()
        if ((fields.voiceString("encoding") ?: HostTranscriptionParams.WAV) != HostTranscriptionParams.WAV) voiceInvalid()
        val bytes = clipBytes(fields.voiceString("audio") ?: voiceInvalid())
        requireMonoWithinDuration(bytes)
        return VoiceClip(bytes, fields.voiceName("language"))
    }

    /** The clip's bytes, refused as too large before and after decoding. */
    private fun clipBytes(encoded: String): ByteArray {
        if (encoded.length > MAX_AUDIO_BASE64_CHARS) voiceRefuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: voiceInvalid()
        if (bytes.size > HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES) voiceRefuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        return bytes
    }

    /** Mono 16-bit PCM WAVE no longer than 30 s by its header. */
    private fun requireMonoWithinDuration(bytes: ByteArray) {
        val audio = (WavCodec.parse(bytes) as? WavParse.Decoded)?.audio ?: voiceInvalid()
        if (audio.format.channels != 1) voiceInvalid()
        val millis = audio.frames.toLong() * MILLIS_PER_SECOND / audio.format.sampleRateHz
        if (millis > HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_MS) voiceRefuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
    }

    /** `voice.speech` params: the text, at most 4096 characters. */
    fun speechText(params: JsonObject?): String {
        val text = params?.voiceString("text")?.takeIf { it.isNotBlank() } ?: voiceInvalid()
        if (text.length > HostVoiceWire.MAX_SPEECH_TEXT_CHARS) voiceRefuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        return text
    }

    /**
     * `voice.speech` params' optional `instructions` (the reply's mood module): passed on only when it
     * names a module by [ToneCues.accepts]; anything else, a free-form description included, is dropped
     * and the text is spoken plainly.
     */
    fun speechInstructions(params: JsonObject?): String? = params?.voiceString("instructions")?.trim()?.takeIf(ToneCues::accepts)

    /** Base64 of the largest accepted clip, rounded up to whole groups. */
    private const val MAX_AUDIO_BASE64_CHARS = (HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES + 2) / 3 * 4
    private const val MILLIS_PER_SECOND = 1_000L
}
