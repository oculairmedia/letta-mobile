package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.endpoint.EndpointTranscriber
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.EnergyDetectorConfig
import com.letta.mobile.data.voice.vad.EnergySpeechDetector
import com.letta.mobile.data.voice.vad.SpeechDetectorFactory
import com.letta.mobile.data.voice.vad.VadSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Turns captured audio into text. Failures are thrown: a
 * [com.letta.mobile.data.voice.endpoint.SpeechEndpointException] or a
 * [com.letta.mobile.data.voice.audio.pcm.VoiceAudioException] keeps its code, anything else is
 * reported as TRANSCRIPTION_FAILED.
 */
fun interface SpeechTranscriber {
    /** The words spoken in [audio] (a WAVE clip). */
    suspend fun transcribe(audio: AudioClip): VoiceText

    /** Adapters. */
    companion object {
        /** Transcribes through an OpenAI-compatible endpoint, with its destination-grant check. */
        fun endpoint(transcriber: EndpointTranscriber): SpeechTranscriber = SpeechTranscriber { transcriber.transcribe(it) }
    }
}

/** How a [VadVoiceEar] listens. */
data class VadEarConfig(
    /** The rate and channel count the ear accepts (mono only); frame size is free, the ear re-blocks. */
    val format: AudioFrameFormat = AudioFrameFormat(),
    /** When turns start and end. */
    val policy: EndOfTurnPolicy = EndOfTurnPolicy(),
    /** How readily the built-in detector hears speech; used by the default [detectors]. */
    val sensitivity: VadSensitivity = VadSensitivity.NORMAL,
    /** The voice-activity detector, created afresh for every listen. */
    val detectors: SpeechDetectorFactory = EnergySpeechDetector.factory(EnergyDetectorConfig.forSensitivity(sensitivity)),
    /** Which transcripts are dropped instead of sent (noise the recogniser wrote words for). */
    val transcripts: TranscriptPolicy = TranscriptPolicy(),
    /** Hears the ear's turn decisions, for diagnostics. */
    val observer: EarObserver = EarObserver.None,
) {
    init {
        require(format.channels == 1) { "The voice-activity ear listens to mono audio" }
    }
}

/**
 * A [VoiceEar] built from a frame [source], a voice-activity detector, an end-of-turn policy and a
 * [transcriber]. It decides end of turn itself (trailing silence, the hard cap, or [commitTurn]) and
 * then transcribes the turn, pre-roll included. A sound that never had enough voice is dropped
 * before transcription, and a transcript the [VadEarConfig.transcripts] policy drops (empty, no
 * words, a stock noise phrase from a short quiet turn) ends the turn as an empty utterance, so the
 * loop listens on and nothing is sent.
 *
 * Events: [VoiceEarEvent.ListeningStarted] with the first frame, [VoiceEarEvent.InputLevel] per
 * frame, [VoiceEarEvent.SpeechStarted] once speech has lasted the policy's minimum (the barge-in
 * signal; raised thresholds while [setOutputActive] is true), [VoiceEarEvent.EndOfTurn] after
 * transcription, and [VoiceEarEvent.Failed].
 *
 * Kept audio: a turn whose transcription failed is kept, in memory only and bounded by the hard
 * cap, under the handle its [VoiceEarEvent.Failed] offers, until the first of: [retry] takes it (one
 * retry; whatever that retry ends in, the audio is then wiped and no new handle is offered), the
 * next turn's speech starts ([VoiceEarEvent.SpeechStarted], in this or any later listen), or
 * [close]. Cancelling a listen does not wipe it: the loop closes the microphone on a failure and
 * calls [retry] only once the reopened ear reports [VoiceEarEvent.ListeningStarted]. [retry] returns
 * false whenever the handle is no longer kept. A transcribed turn, and a retry cancelled mid-way,
 * are wiped at once; utterances carry no capture handle. A failure that arrives after the next
 * turn's speech has started offers no handle. At most four finished turns wait for transcription;
 * a turn beyond that is wiped and reported as TRANSCRIPTION_FAILED without a handle.
 *
 * Collecting [listen] opens the source; cancelling releases it before the collection completes,
 * and nothing is emitted after. A source that fails or ends reports one [VoiceEarEvent.Failed] with
 * its code (UNSUPPORTED_FORMAT when the source's rate or channels differ from [VadEarConfig.format])
 * and then holds until cancelled. All timing comes from sample positions, never a clock.
 */
class VadVoiceEar(
    private val source: AudioFrameSource,
    private val transcriber: SpeechTranscriber,
    private val config: VadEarConfig = VadEarConfig(),
) : VoiceEar, AutoCloseable {
    private val memory = EarMemory()

    override fun listen(): Flow<VoiceEarEvent> = channelFlow { EarSession(EarParts(source, transcriber, config, memory), this).run() }

    override fun commitTurn() {
        memory.commits.trySend(Unit)
    }

    override fun setOutputActive(active: Boolean) {
        memory.outputActive.value = active
    }

    override fun retry(handle: CapturedAudioHandle): Boolean {
        val held = memory.take(handle) ?: return false
        return memory.requestRetry(held)
    }

    /** Wipes any retained capture and refuses further listens and retries. */
    override fun close() {
        memory.close()
    }
}
