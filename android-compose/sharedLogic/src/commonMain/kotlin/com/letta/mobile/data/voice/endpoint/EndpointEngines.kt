package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.pcm.WavStreamDecoder
import com.letta.mobile.data.voice.consent.VoiceEndpointGrantSource
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.tone.ToneCue
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
    /**
     * The text spoken in [audio], with the recogniser's own numbers when the server reports them;
     * [purpose] decides how much cleanup the server is asked for.
     */
    suspend fun transcribe(audio: AudioClip, purpose: TranscriptionPurpose = TranscriptionPurpose.TURN): SpeechTranscript {
        client.requireGrant(grants, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION, SpeechOperation.TRANSCRIPTION)
        return client.transcribe(TranscriptionRequest(audio, settings.transcriptionModel, settings.language, purpose))
    }
}

/**
 * The synthesis half of [EndpointVoiceMouth]: one text segment to audio through the client's speech
 * endpoint (for example a Qwen3-TTS server with a cloned voice), as one clip ([synthesize]) or as
 * PCM while it streams in ([stream]), at an optional [SpeechSpeed]. Checks for a grant naming that endpoint for
 * [VoiceEndpointPayload.TEXT_FOR_SYNTHESIS] before every request.
 */
class EndpointSpeechSynthesizer(
    private val client: SpeechEndpointClient,
    private val grants: VoiceEndpointGrantSource,
    private val settings: VoiceEndpointSettings,
    private val encoding: AudioEncoding = AudioEncoding.MP3,
) : StreamingSpeechSynthesizer {
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
    override fun stream(text: VoiceText, speed: SpeechSpeed): Flow<PcmChunk> = stream(text, speed, null)

    /** As [stream], with [tone] sent as the request's `instructions` when there is one. */
    override fun stream(text: VoiceText, speed: SpeechSpeed, tone: ToneCue?): Flow<PcmChunk> = channelFlow {
        client.requireGrant(grants, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS, SpeechOperation.SPEECH)
        val decoder = WavStreamDecoder()
        val request = SpeechRequest(text, settings.speechModel, settings.voice, AudioEncoding.WAV, speed, tone)
        client.synthesize(request) { chunk -> decoder.push(chunk.bytes)?.let { send(it) } }
        if (!decoder.started) throw SpeechEndpointException(SpeechOperation.SPEECH, VoiceErrorCode.BAD_RESPONSE)
    }

    /**
     * Opens (or reuses) the connection to the speech endpoint ahead of the first segment, so the
     * reply's first request skips the connection set-up: a reachability request that carries no
     * text or audio. Only sent where the person granted text for synthesis to that endpoint; fails
     * like any request (the caller ignores the outcome).
     */
    override suspend fun warm() {
        client.requireGrant(grants, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS, SpeechOperation.REACHABILITY)
        client.checkReachable()
    }
}
