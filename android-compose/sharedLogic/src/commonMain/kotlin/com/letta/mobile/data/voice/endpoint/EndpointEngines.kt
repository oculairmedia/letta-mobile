package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.audio.pcm.WavStreamDecoder
import com.letta.mobile.data.voice.consent.VoiceEndpointGrantSource
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** Refuses a request whose destination the person has not granted for its payload. */
private fun SpeechEndpointClient.requireGrant(
    grants: VoiceEndpointGrantSource,
    payload: VoiceEndpointPayload,
    operation: SpeechOperation,
) {
    if (!grants.current().allows(endpoint, payload)) {
        throw SpeechEndpointException(operation, VoiceErrorCode.PERMISSION_REQUIRED)
    }
}

/**
 * Turns captured audio into text through the client's transcription endpoint. Before every request
 * it checks for a grant naming that endpoint for [VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION];
 * without one it fails with PERMISSION_REQUIRED and sends nothing. Model and language come from
 * [settings]; the destination is the client's.
 */
class EndpointTranscriber(
    private val client: SpeechEndpointClient,
    private val grants: VoiceEndpointGrantSource,
    private val settings: VoiceEndpointSettings,
) {
    /** The text spoken in [audio]. */
    suspend fun transcribe(audio: AudioClip): VoiceText {
        client.requireGrant(grants, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION, SpeechOperation.TRANSCRIPTION)
        return client.transcribe(TranscriptionRequest(audio, settings.transcriptionModel, settings.language))
    }
}

/**
 * The synthesis half of [EndpointVoiceMouth]: one text segment to audio through the client's speech
 * endpoint (for example a Qwen3-TTS server with a cloned voice), as one clip ([synthesize]) or as
 * PCM while it streams in ([stream]). Checks for a grant naming that endpoint for
 * [VoiceEndpointPayload.TEXT_FOR_SYNTHESIS] before every request.
 */
class EndpointSpeechSynthesizer(
    private val client: SpeechEndpointClient,
    private val grants: VoiceEndpointGrantSource,
    private val settings: VoiceEndpointSettings,
    private val encoding: AudioEncoding = AudioEncoding.MP3,
) {
    /** [text] as audio. */
    suspend fun synthesize(text: VoiceText): AudioClip {
        client.requireGrant(grants, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS, SpeechOperation.SPEECH)
        return client.synthesizeClip(SpeechRequest(text, settings.speechModel, settings.voice, encoding))
    }

    /**
     * [text] as 16-bit PCM, emitted as each part of the response arrives rather than after the
     * whole body. Always asks for WAVE, whatever this synthesizer's clip encoding: the streamed
     * header carries the sample rate, which headerless PCM would not (and compressed formats are
     * generated whole before they are sent). Collecting sends the request; cancelling the collector
     * closes it. A response that is not 16-bit PCM WAVE fails with a [SpeechEndpointException]
     * carrying BAD_RESPONSE or UNSUPPORTED_FORMAT.
     */
    fun stream(text: VoiceText): Flow<PcmChunk> = channelFlow {
        client.requireGrant(grants, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS, SpeechOperation.SPEECH)
        val decoder = WavStreamDecoder()
        val request = SpeechRequest(text, settings.speechModel, settings.voice, AudioEncoding.WAV)
        client.synthesize(request) { chunk -> decoded { decoder.push(chunk.bytes) }?.let { send(it) } }
        decoded { decoder.finish() }
    }

    /** Runs a decoder step, reporting a refused stream as the endpoint's bad answer. */
    private inline fun <T> decoded(step: () -> T): T = try {
        step()
    } catch (refused: VoiceAudioException) {
        throw SpeechEndpointException(SpeechOperation.SPEECH, refused.code)
    }
}
