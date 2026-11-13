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
 * test drives it without a clock. A hop at or above the enter threshold opens a candidate; voiced
 * hops (at or above the exit threshold) are counted. Before [onsetSustain] of voice has built up, a
 * gap longer than [onsetGap] drops the candidate (a click, a knock); after it, the candidate becomes
 * a turn once [minVoiced] of voice has been heard, and is dropped if [trailingSilence] passes first.
 * A dropped candidate is never transcribed. A turn ends after [trailingSilence] without voice (the
 * hangover that keeps pauses between words inside one turn) or at [maxTurn]. While the app speaks,
 * the raised [echoGuard] thresholds apply and a turn needs [bargeInSustain] of voice.
 */
data class EndOfTurnPolicy(
    /** Thresholds while the app is silent. */
    val thresholds: SpeechThresholds = SpeechThresholds(),
    /** Thresholds while the app's own speech plays (echo guard). */
    val echoGuard: SpeechThresholds = SpeechThresholds(enter = 0.8f, exit = 0.65f),
    /** Voice a candidate must build up before it may pause for longer than [onsetGap]. */
    val onsetSustain: Duration = 192.milliseconds,
    /** Before its onset, a candidate silent for longer than this is dropped. */
    val onsetGap: Duration = 96.milliseconds,
    /** Voice a candidate needs to count as a turn; shorter bursts are dropped untranscribed. */
    val minVoiced: Duration = 320.milliseconds,
    /** While the app speaks, voice needed before a candidate interrupts it (barge-in). */
    val bargeInSustain: Duration = 400.milliseconds,
    /** This much silence after voice ends the turn. */
    val trailingSilence: Duration = 900.milliseconds,
    /** Audio kept from before the first speech hop, so the first syllable is not clipped. */
    val preRoll: Duration = 160.milliseconds,
    /** A turn is ended at this length whatever is heard. */
    val maxTurn: Duration = 20.seconds,
)

/** Why a heard sound was not passed on as a turn. */
enum class TurnDiscardReason {
    /** The sound never built up a sustained onset (a click, a knock, a short noise). */
    NO_ONSET,

    /** The sound had an onset but too little voice to be a turn. */
    TOO_LITTLE_VOICE,

    /** The transcript was empty. */
    EMPTY_TRANSCRIPT,

    /** The transcript held no words (punctuation, a stray letter). */
    NO_WORDS,

    /** A short, quiet turn transcribed as one of the recogniser's stock noise phrases. */
    STOCK_PHRASE,

    /**
     * Half-duplex: the sound was captured while the app's own speech was queued or playing (or in
     * the tail after it), so it may be the app hearing itself; it is never transcribed.
     */
    OUTPUT_ACTIVE,
}

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

    /** The candidate had enough voice: the person is speaking. */
    data object Confirmed : TurnSignal

    /** The candidate was dropped for [reason]: discard what was recorded. */
    data class Abandoned(val reason: TurnDiscardReason) : TurnSignal

    /** The turn is over. */
    data class Ended(val reason: TurnEndReason) : TurnSignal
}

/** One judged hop: its probability, where it ends and whether the app was speaking. */
internal data class HopObservation(val probability: SpeechProbability, val end: SamplePosition, val outputActive: Boolean)

/** Applies an [EndOfTurnPolicy] hop by hop. Pure: no clock, no audio, no I/O. */
internal class TurnTracker(private val policy: EndOfTurnPolicy, format: AudioFrameFormat, private val hop: DetectorHop) {
    private val rate = format.sampleRateHz.toDouble()
    private val onset = samplesOf(policy.onsetSustain)
    private val onsetGap = samplesOf(policy.onsetGap)
    private val minVoiced = samplesOf(policy.minVoiced)
    private val bargeIn = maxOf(minVoiced, samplesOf(policy.bargeInSustain))
    private val trailing = samplesOf(policy.trailingSilence)
    private val cap = samplesOf(policy.maxTurn)
    private var phase: TurnPhase = TurnPhase.Quiet

    /** True while a turn is being recorded (candidate or speaking). */
    val recording: Boolean get() = phase != TurnPhase.Quiet

    /** True while a confirmed turn is open (the person was reported speaking). */
    val confirmed: Boolean get() = phase is TurnPhase.Speaking

    /** Forgets the candidate or turn being tracked, without a signal (the ear reports why). */
    fun drop() {
        phase = TurnPhase.Quiet
    }

    /** Voiced samples of the candidate or turn being tracked, or of the last one that ended or was dropped. */
    var voicedSamples: Long = 0
        private set

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
        voicedSamples = hop.samples.toLong()
        phase = TurnPhase.Candidate(observed.end.value - hop.samples, observed.end.value)
        return TurnSignal.Candidate
    }

    private fun candidate(current: TurnPhase.Candidate, observed: HopObservation): TurnSignal {
        val end = observed.end.value
        val voiced = observed.probability.value >= thresholds(observed).exit
        if (voiced) voicedSamples += hop.samples
        val lastVoice = if (voiced) end else current.lastVoice
        val needed = if (observed.outputActive) bargeIn else minVoiced
        if (voicedSamples >= needed) {
            phase = TurnPhase.Speaking(current.start, lastVoice)
            return TurnSignal.Confirmed
        }
        val beforeOnset = voicedSamples < onset
        if (end - lastVoice <= if (beforeOnset) onsetGap else trailing) {
            phase = TurnPhase.Candidate(current.start, lastVoice)
            return TurnSignal.None
        }
        phase = TurnPhase.Quiet
        return TurnSignal.Abandoned(if (beforeOnset) TurnDiscardReason.NO_ONSET else TurnDiscardReason.TOO_LITTLE_VOICE)
    }

    private fun speaking(current: TurnPhase.Speaking, observed: HopObservation): TurnSignal {
        val end = observed.end.value
        val voiced = observed.probability.value >= policy.thresholds.exit
        if (voiced) voicedSamples += hop.samples
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

        data class Candidate(val start: Long, val lastVoice: Long) : TurnPhase

        data class Speaking(val start: Long, val lastVoice: Long) : TurnPhase
    }
}
