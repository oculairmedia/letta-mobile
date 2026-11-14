package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.audio.pcm.WavParse
import com.letta.mobile.data.voice.vad.DetectorHop
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.SpeechDetector
import com.letta.mobile.data.voice.vad.SpeechDetectorFactory
import com.letta.mobile.data.voice.vad.SpeechProbability
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How a scripted source ends once its frames are out. */
internal sealed interface SourceEnding {
    /** Stays open until cancelled, like a live microphone. */
    data object Hold : SourceEnding

    /** Fails with [code]. */
    data class Fail(val code: VoiceErrorCode) : SourceEnding

    /** Ends on its own. */
    data object Complete : SourceEnding
}

/** A frame source playing [script], counting opens and tracking whether it is open. */
internal class ScriptedSource(
    var script: List<ShortArray>,
    var ending: SourceEnding = SourceEnding.Hold,
    override val format: AudioFrameFormat = AudioFrameFormat(),
) : AudioFrameSource {
    var opens = 0
        private set
    var open = false
        private set

    override fun frames(): Flow<AudioFrame> = flow {
        opens++
        open = true
        try {
            var position = 0L
            script.forEach { samples ->
                emit(AudioFrame(samples, SamplePosition(position)))
                position += samples.size
            }
            when (val end = ending) {
                SourceEnding.Hold -> awaitCancellation()
                is SourceEnding.Fail -> throw VoiceAudioException(end.code)
                SourceEnding.Complete -> Unit
            }
        } finally {
            open = false
        }
    }
}

/** [count] frames of 512 samples all equal to [value]; with [ValueDetector] the probability is value / 1000. */
internal fun frames(value: Int, count: Int): List<ShortArray> = List(count) { ShortArray(512) { value.toShort() } }

/** A turn: pre-roll fodder, 384 ms of speech (confirmed after 300 ms), then enough silence to end it. */
internal fun turn(): List<ShortArray> = frames(PRE_ROLL_VALUE, 4) + frames(SPEECH_VALUE, 12) + frames(0, 32)

/** A turn that never ends on its own. */
internal fun openTurn(): List<ShortArray> = frames(PRE_ROLL_VALUE, 4) + frames(SPEECH_VALUE, 12)

internal const val PRE_ROLL_VALUE = 7
internal const val SPEECH_VALUE = 900

/** A detector reading the probability off the first sample: value / 1000. */
internal class ValueDetector : SpeechDetector {
    override val hop = DetectorHop(256)

    override fun probability(samples: ShortArray): SpeechProbability = SpeechProbability.clamped(samples[0] / 1_000f)

    override fun reset() = Unit
}

/** Answers transcriptions from [outcomes] in order, keeping a decoded copy of every clip. */
/** One scripted transcription: the words, or the failure to throw. */
internal class Outcome(val words: VoiceText?, val error: Throwable?)

internal class ScriptedTranscriber(vararg outcomes: Outcome) : SpeechTranscriber {
    private val queue = ArrayDeque(outcomes.toList())
    val clips = mutableListOf<PcmAudio>()

    override suspend fun transcribe(audio: AudioClip): VoiceText {
        clips += (WavCodec.parse(audio.bytes) as WavParse.Decoded).audio
        val next = queue.removeFirst()
        next.error?.let { throw it }
        return checkNotNull(next.words)
    }
}

internal fun heard(words: String): Outcome = Outcome(VoiceText(words), null)

internal fun failing(error: Throwable): Outcome = Outcome(null, error)

internal fun valueEar(source: AudioFrameSource, transcriber: SpeechTranscriber): VadVoiceEar =
    VadVoiceEar(source, transcriber, VadEarConfig(policy = EndOfTurnPolicy(), detectors = SpeechDetectorFactory { ValueDetector() }))
