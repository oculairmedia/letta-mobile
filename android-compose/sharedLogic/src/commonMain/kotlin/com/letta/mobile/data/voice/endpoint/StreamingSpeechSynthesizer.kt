package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.PcmChunk
import kotlinx.coroutines.flow.Flow

/**
 * Speech output for [EndpointVoiceMouth]: one text segment to 16-bit PCM, emitted while it streams
 * in. The direct speech server ([EndpointSpeechSynthesizer]) and the host's shared server (through
 * the host's voice endpoint) both stand behind it, so the mouth does not know which one it talks to.
 * A failure is a [SpeechEndpointException] carrying the voice loop's code.
 */
interface StreamingSpeechSynthesizer {
    /**
     * [text] as PCM at [speed], as each part arrives. Collecting sends the request; cancelling the
     * collector cancels it. A synthesizer that cannot change speed speaks at its normal speed.
     */
    fun stream(text: VoiceText, speed: SpeechSpeed = SpeechSpeed.Normal): Flow<PcmChunk>

    /**
     * Prepares the connection ahead of the first segment, sending no text or audio. Callers ignore
     * the outcome; the default does nothing.
     */
    suspend fun warm() = Unit
}
