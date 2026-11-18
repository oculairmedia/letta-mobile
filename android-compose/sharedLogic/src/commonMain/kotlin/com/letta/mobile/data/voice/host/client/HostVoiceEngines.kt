package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechOperation
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.endpoint.StreamingSpeechSynthesizer
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceException
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Listening through the host: each turn's clip goes to the host's shared speech server with
 * [HostVoiceEndpoint.transcribe], live partials included. Before every request it checks this
 * device's own one-time acknowledgement that its microphone audio may go to the shared server
 * ([acknowledged]); without it the request is refused with PERMISSION_REQUIRED and nothing is sent.
 * Host failures become [SpeechEndpointException]s carrying the voice loop's code, as the direct
 * transcriber's do.
 */
class HostSpeechTranscriber(
    private val endpoint: HostVoiceEndpoint,
    private val language: SpeechLanguage? = null,
    private val acknowledged: () -> Boolean,
) : SpeechTranscriber {
    override suspend fun transcribe(audio: AudioClip): SpeechTranscript {
        if (!acknowledged()) throw SpeechEndpointException(SpeechOperation.TRANSCRIPTION, VoiceErrorCode.PERMISSION_REQUIRED)
        return try {
            endpoint.transcribe(audio, language)
        } catch (failure: HostVoiceException) {
            throw SpeechEndpointException(SpeechOperation.TRANSCRIPTION, failure.code.voiceCode)
        }
    }
}

/**
 * Speaking through the host: each segment is streamed from the host's shared speech server with
 * [HostVoiceEndpoint.synthesize]. The host checks the shared grant for reply text; the voice-endpoint
 * contract has no speed, so segments are spoken at the server's normal speed. Host failures become
 * [SpeechEndpointException]s carrying the voice loop's code. There is nothing to warm: the request
 * rides the host connection that is already open.
 */
class HostSpeechSynthesizer(private val endpoint: HostVoiceEndpoint) : StreamingSpeechSynthesizer {
    override fun stream(text: VoiceText, speed: SpeechSpeed): Flow<PcmChunk> = endpoint.synthesize(text).catch { failure ->
        if (failure !is HostVoiceException) throw failure
        throw SpeechEndpointException(SpeechOperation.SPEECH, failure.code.voiceCode)
    }
}
