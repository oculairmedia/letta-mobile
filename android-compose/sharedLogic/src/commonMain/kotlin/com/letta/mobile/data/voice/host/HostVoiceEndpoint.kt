package com.letta.mobile.data.voice.host

import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.tone.ToneCue
import kotlinx.coroutines.flow.Flow

/**
 * The speech endpoint as the host keeps it: one setup shared by every device, with the API key held
 * only by the host, and speech requests sent through the host, which calls the speech server.
 *
 * This is the client side of the voice-endpoint v1 contract. Reads need the chat read capability,
 * setup writes need admin rights, and speech requests need the chat send capability. Every failure
 * is a [HostVoiceException] carrying one [HostVoiceErrorCode]; nothing returned or thrown carries the
 * key, audio, transcript or reply text.
 *
 * [transcribe] has the shape of the voice loop's speech transcriber and [synthesize] the shape of the
 * endpoint synthesizer's stream, so either can back the existing loop without the loop knowing
 * whether it talks to the speech server directly or through the host.
 */
interface HostVoiceEndpoint {
    /** The shared setup as this device may see it: never the key, not even its length. */
    suspend fun status(): HostVoiceSetupStatus

    /** Stores [command] on the host and returns the setup that is now in force. */
    suspend fun writeSetup(command: HostVoiceSetupCommand): HostVoiceSetupStatus

    /**
     * Removes the shared setup, its key and its grants. With [expectedRevision], fails with
     * [HostVoiceErrorCode.REVISION_CONFLICT] when the setup changed since that revision was read.
     */
    suspend fun clearSetup(expectedRevision: String? = null): HostVoiceSetupStatus

    /**
     * Asks the host whether a speech server answers, sending no audio or text. With no [candidate],
     * the stored setup is checked and its reachability updated; a candidate is checked and forgotten.
     */
    suspend fun testConnection(candidate: HostVoiceConnectionCandidate? = null): HostVoiceTestResult

    /** The models and voices the stored speech server offers. */
    suspend fun models(): HostVoiceModels

    /**
     * The words spoken in [audio], a mono 16-bit WAVE clip, with the recogniser's per-segment numbers
     * when the speech server reports them. [language] overrides the stored hint for this request.
     */
    suspend fun transcribe(audio: AudioClip, language: SpeechLanguage? = null): SpeechTranscript

    /**
     * [text] spoken in the stored voice, as 16-bit PCM emitted while it streams in. Collecting sends
     * the request; cancelling the collector cancels it on the host and at the speech server.
     */
    fun synthesize(text: VoiceText): Flow<PcmChunk>

    /**
     * [text] spoken as [tone] asks: the cue goes in the request as `instructions` for the host to pass
     * to the speech server (nothing is sent for null). An endpoint that cannot carry it speaks as
     * [synthesize] does; the default does.
     */
    fun synthesize(text: VoiceText, tone: ToneCue?): Flow<PcmChunk> = synthesize(text)
}
