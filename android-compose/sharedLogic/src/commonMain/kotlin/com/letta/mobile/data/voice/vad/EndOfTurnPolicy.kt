package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.SamplePosition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** Speech-probability thresholds with hysteresis: enter at [enter], stay while at or above [exit]. */
data class SpeechThresholds(
    /** A hop at or above this may start speech. */
    val enter: Float = 0.5f,
    /** Speech continues while hops stay at or above this. */
    val exit: Float = 0.35f,
) {
    init {
        require(exit <= enter) { "Exit threshold above enter threshold" }
    }
}

/**
 * When a turn starts and ends. Every duration is audio time, measured from sample positions, so a
 * test drives it without a clock. Defaults follow the prior-art study: hysteresis 0.5 / 0.35,
 * 300 ms of speech before a turn counts, 900 ms of trailing silence ends it, 160 ms of audio before
 * the first speech is kept, a 20 s hard cap, and while the app speaks a raised threshold (echo
 * guard) instead of a deaf microphone.
 */
data class EndOfTurnPolicy(
    /** Thresholds while the app is silent. */
    val thresholds: SpeechThresholds = SpeechThresholds(),
    /** Thresholds while the app's own speech plays (echo guard). */
    val echoGuard: SpeechThresholds = SpeechThresholds(enter = 0.8f, exit = 0.65f),
    /** Speech shorter than this is a blip, not a turn. */
    val minSpeech: Duration = 300.milliseconds,
    /** While the app speaks, speech must last this long before it interrupts (barge-in). */
    val bargeInSustain: Duration = 300.milliseconds,
    /** This much silence after speech ends the turn. */
    val trailingSilence: Duration = 900.milliseconds,
    /** Audio kept from before the first speech hop, so the first syllable is not clipped. */
    val preRoll: Duration = 160.milliseconds,
    /** A turn is ended at this length whatever is heard. */
    val maxTurn: Duration = 20.seconds,
)

/** Why a turn ended. */
enum class TurnEndReason {
    /** Trailing silence after speech. */
    SILENCE,

    /** The hard cap on turn length. */
    HARD_CAP,

    /** The person committed the turn (push-to-talk release). */
    COMMITTED,
}

/** What one hop (or a commit) changed. */
internal sealed interface TurnSignal {
    /** Nothing changed. */
    data object None : TurnSignal

    /** A hop crossed the enter threshold: start recording, with the pre-roll. */
    data object Candidate : TurnSignal

    /** The candidate lasted long enough: the person is speaking. */
    data object Confirmed : TurnSignal

    /** The candidate fell silent too early: discard what was recorded. */
    data object Abandoned : TurnSignal

    /** The turn is over. */
    data class Ended(val reason: TurnEndReason) : TurnSignal
}

/** One judged hop: its probability, where it ends and whether the app was speaking. */
internal data class HopObservation(val probability: SpeechProbability, val end: SamplePosition, val outputActive: Boolean)

/** Applies an [EndOfTurnPolicy] hop by hop. Pure: no clock, no audio, no I/O. */
internal class TurnTracker(private val policy: EndOfTurnPolicy, format: AudioFrameFormat, private val hop: DetectorHop) {
    private val rate = format.sampleRateHz.toDouble()
    private val minSpeech = samplesOf(policy.minSpeech)
    private val bargeIn = maxOf(minSpeech, samplesOf(policy.bargeInSustain))
    private val trailing = samplesOf(policy.trailingSilence)
    private val cap = samplesOf(policy.maxTurn)
    private var phase: TurnPhase = TurnPhase.Quiet

    /** True while a turn is being recorded (candidate or speaking). */
    val recording: Boolean get() = phase != TurnPhase.Quiet

    fun onHop(hopResult: HopObservation): TurnSignal = when (val current = phase) {
        TurnPhase.Quiet -> quiet(hopResult)
        is TurnPhase.Candidate -> candidate(current, hopResult)
        is TurnPhase.Speaking -> speaking(current, hopResult)
    }

    /** The person committed the turn: it ends now if anything was heard. */
    fun commit(): TurnSignal {
        if (phase == TurnPhase.Quiet) return TurnSignal.None
        phase = TurnPhase.Quiet
        return TurnSignal.Ended(TurnEndReason.COMMITTED)
    }

    private fun quiet(observed: HopObservation): TurnSignal {
        if (observed.probability.value < thresholds(observed).enter) return TurnSignal.None
        phase = TurnPhase.Candidate(observed.end.value - hop.samples)
        return TurnSignal.Candidate
    }

    private fun candidate(current: TurnPhase.Candidate, observed: HopObservation): TurnSignal {
        if (observed.probability.value < thresholds(observed).exit) {
            phase = TurnPhase.Quiet
            return TurnSignal.Abandoned
        }
        val needed = if (observed.outputActive) bargeIn else minSpeech
        if (observed.end.value - current.start < needed) return TurnSignal.None
        phase = TurnPhase.Speaking(current.start, observed.end.value)
        return TurnSignal.Confirmed
    }

    private fun speaking(current: TurnPhase.Speaking, observed: HopObservation): TurnSignal {
        val end = observed.end.value
        val voiced = observed.probability.value >= policy.thresholds.exit
        val lastVoice = if (voiced) end else current.lastVoice
        phase = TurnPhase.Speaking(current.start, lastVoice)
        val reason = when {
            end - current.start >= cap -> TurnEndReason.HARD_CAP
            end - lastVoice >= trailing -> TurnEndReason.SILENCE
            else -> return TurnSignal.None
        }
        phase = TurnPhase.Quiet
        return TurnSignal.Ended(reason)
    }

    private fun thresholds(observed: HopObservation): SpeechThresholds =
        if (observed.outputActive) policy.echoGuard else policy.thresholds

    private fun samplesOf(duration: Duration): Long = (duration.toDouble(DurationUnit.SECONDS) * rate).toLong()

    private sealed interface TurnPhase {
        data object Quiet : TurnPhase

        data class Candidate(val start: Long) : TurnPhase

        data class Speaking(val start: Long, val lastVoice: Long) : TurnPhase
    }
}
