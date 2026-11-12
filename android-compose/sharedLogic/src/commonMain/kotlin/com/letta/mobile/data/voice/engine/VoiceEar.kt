package com.letta.mobile.data.voice.engine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import kotlinx.coroutines.flow.Flow

/**
 * Hearing: the engine that turns the person's speech into turns.
 *
 * The ear OWNS its audio source (a microphone capture, a realtime model's input stream). Collecting
 * [listen] opens it; cancelling the collection closes it, and the collection must not return until
 * the source is released, so a second [listen] can never race a capture that is still running. The
 * loop bounds that wait and reports [com.letta.mobile.data.voice.VoiceErrorCode.UNRESPONSIVE] past it.
 *
 * The flow keeps listening across turns until cancelled: an engine that recognises one utterance at
 * a time restarts itself internally. Completing normally while collected is treated as a failure.
 *
 * End of turn is the ear's decision ([VoiceEarEvent.EndOfTurn]): a speech-to-text ear may use its
 * recogniser's endpointing, a realtime model its semantic turn detection. The loop has no silence
 * timer.
 */
interface VoiceEar {
    /** Opens the audio source and reports what is heard until the collection is cancelled. */
    fun listen(): Flow<VoiceEarEvent>

    /**
     * The person signalled they finished (a push-to-talk release or an explicit "send"). The ear
     * should finish the current turn now and emit [VoiceEarEvent.EndOfTurn] (or nothing when no
     * speech was heard).
     */
    fun commitTurn()

    /**
     * Echo-suppression hook: true while the app's own speech is playing. An engine can apply echo
     * cancellation or raise its speech threshold so it does not hear the app (self-barge-in).
     * The loop only reports the fact; it does not filter audio.
     */
    fun setOutputActive(active: Boolean) = Unit

    /**
     * Re-transcribes the retained capture [handle] (offered by [VoiceEarEvent.Failed.retry]) and
     * reports the result through the running [listen] flow. The ear keeps one capture for one
     * retry until its next capture starts. Returns false when the capture is no longer held.
     */
    fun retry(handle: CapturedAudioHandle): Boolean = false
}

/** What an ear reports while it listens. */
sealed interface VoiceEarEvent {
    /** The audio source is open. */
    data object ListeningStarted : VoiceEarEvent

    /** The input [level], for a meter. */
    data class InputLevel(
        /** The current loudness. */
        val level: AudioLevel,
    ) : VoiceEarEvent

    /** A provisional transcript of the turn so far. */
    data class PartialTranscript(
        /** The words so far. */
        val text: VoiceText,
    ) : VoiceEarEvent

    /** The person started speaking. While the app is speaking this is a barge-in. */
    data object SpeechStarted : VoiceEarEvent

    /** The ear decided the person finished a turn. */
    data class EndOfTurn(
        /** The turn, with whatever the ear decided about it. */
        val utterance: VoiceUtterance,
    ) : VoiceEarEvent

    /** The ear failed; [retry] names a retained capture that can be retried once. */
    data class Failed(
        /** What failed. */
        val error: VoiceError,
        /** The capture the ear still holds for one retry; null when there is none. */
        val retry: CapturedAudioHandle? = null,
    ) : VoiceEarEvent
}
