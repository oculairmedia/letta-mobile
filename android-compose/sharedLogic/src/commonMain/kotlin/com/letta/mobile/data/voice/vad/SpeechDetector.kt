package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import kotlin.jvm.JvmInline

/** How many samples a detector judges at a time (mono, at the detector's rate). */
@JvmInline
value class DetectorHop(
    /** Samples per step; positive. */
    val samples: Int,
) {
    init {
        require(samples > 0) { "Detector hop must be positive" }
    }
}

/** How likely one hop holds speech, from 0 (certainly not) to 1 (certainly). */
@JvmInline
value class SpeechProbability(
    /** The probability, within 0..1. */
    val value: Float,
) {
    init {
        require(value in 0f..1f) { "Speech probability outside 0..1" }
    }

    /** Shared probabilities. */
    companion object {
        /** Certainly not speech. */
        val None: SpeechProbability = SpeechProbability(0f)

        /** [raw] forced into 0..1; NaN is [None]. */
        fun clamped(raw: Float): SpeechProbability = if (raw.isNaN()) None else SpeechProbability(raw.coerceIn(0f, 1f))
    }
}

/**
 * A voice-activity detector: one hop of mono PCM in, a speech probability out. It touches no
 * device, file, clock or network, and decides nothing about turns (that is the ear's end-of-turn
 * policy), so a model-based detector (for example Silero VAD with a 512-sample hop at 16 kHz) can
 * replace the built-in energy detector without changing the ear: the ear re-blocks whatever frames
 * its source produces into [hop]-sized runs.
 *
 * A detector may keep state between hops (a noise floor, a model's recurrent state); [reset]
 * forgets it. Used from one coroutine at a time.
 */
interface SpeechDetector {
    /** The samples [probability] expects per call. */
    val hop: DetectorHop

    /** The probability that [samples] (exactly [hop] samples) hold speech. */
    fun probability(samples: ShortArray): SpeechProbability

    /** Forgets everything learned so far; called when a new capture starts. */
    fun reset()
}

/** Creates a detector for one capture in [AudioFrameFormat]; the ear asks for a new one per listen. */
fun interface SpeechDetectorFactory {
    /** A fresh detector for audio in [format]. */
    fun create(format: AudioFrameFormat): SpeechDetector
}
