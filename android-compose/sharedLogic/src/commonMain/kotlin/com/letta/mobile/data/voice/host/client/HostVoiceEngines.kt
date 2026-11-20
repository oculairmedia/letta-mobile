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
import com.letta.mobile.data.voice.host.SpeechContext
import com.letta.mobile.data.voice.tone.ToneCue
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
        return runCatching { endpoint.transcribe(audio, language) }.getOrElse { throw asSpeechFailure(it, SpeechOperation.TRANSCRIPTION) }
    }
}

/**
 * Speaking through the host: each segment is streamed from the host's shared speech server with
 * [HostVoiceEndpoint.synthesize]. The host checks the shared grant for reply text; the voice-endpoint
 * contract has no speed, so segments are spoken at the server's normal speed. Host failures become
 * [SpeechEndpointException]s carrying the voice loop's code. There is nothing to warm: the request
 * rides the host connection that is already open.
 */
class HostSpeechSynthesizer(
    private val endpoint: HostVoiceEndpoint,
    /**
     * The agent answering the reply being spoken, as its session captured it when the reply started
     * ([com.letta.mobile.data.voice.responder.VoiceChatSession.agentId]); null speaks in the stored voice.
     */
    private val agentId: () -> String? = { null },
) : StreamingSpeechSynthesizer {
    override fun stream(text: VoiceText, speed: SpeechSpeed): Flow<PcmChunk> = stream(text, speed, null)

    /** As [stream], with [tone] passed to the host as the request's `instructions`. */
    override fun stream(text: VoiceText, speed: SpeechSpeed, tone: ToneCue?): Flow<PcmChunk> =
        endpoint.synthesize(text, SpeechContext(tone, agentId())).catch { throw asSpeechFailure(it, SpeechOperation.SPEECH) }
}

/** A host failure as the endpoint exception the voice loop reads its code from; anything else, cancellation included, as it was. */
private fun asSpeechFailure(failure: Throwable, operation: SpeechOperation): Throwable =
    if (failure is HostVoiceException) SpeechEndpointException(operation, failure.code.voiceCode) else failure
