package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.SamplePosition
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** [hops] hops at [probability]. */
private data class Run(val hops: Int, val probability: Float)

/** A signal at the hop it happened (16 ms hops at 16 kHz). */
private data class At(val hop: Int, val signal: TurnSignal)

private class PolicyCase(
    val name: String,
    val runs: List<Run>,
    val expected: List<At>,
    val outputActive: Boolean = false,
    val policy: EndOfTurnPolicy = EndOfTurnPolicy(),
)

private val hop = DetectorHop(256)

private fun ended(reason: TurnEndReason) = TurnSignal.Ended(reason)

private fun abandoned(reason: TurnDiscardReason) = TurnSignal.Abandoned(reason)

private fun replay(case: PolicyCase): List<At> {
    val tracker = TurnTracker(case.policy, VadSignals.format, hop)
    val probabilities = case.runs.flatMap { run -> List(run.hops) { run.probability } }
    return probabilities.mapIndexedNotNull { index, probability ->
        val end = SamplePosition((index + 1L) * hop.samples)
        val signal = tracker.onHop(HopObservation(SpeechProbability(probability), end, case.outputActive))
        if (signal == TurnSignal.None) null else At(index, signal)
    }
}

class TurnTrackerTest {
    // At 16 kHz with 256-sample hops: 320 ms of voice (20 hops) confirms a turn at hop 19; before
    // 192 ms of voice (12 hops) a gap of more than 96 ms (7 hops) drops the candidate; 900 ms of
    // silence after the last voiced hop v ends the turn (or drops a candidate past its onset) at
    // hop v + 57.
    private val cases = listOf(
        PolicyCase(
            "a blip shorter than the onset is dropped",
            listOf(Run(10, 0.9f), Run(20, 0f)),
            listOf(At(0, TurnSignal.Candidate), At(16, abandoned(TurnDiscardReason.NO_ONSET))),
        ),
        PolicyCase(
            "a burst past its onset but with too little voice is dropped",
            listOf(Run(15, 0.9f), Run(100, 0f)),
            listOf(At(0, TurnSignal.Candidate), At(71, abandoned(TurnDiscardReason.TOO_LITTLE_VOICE))),
        ),
        PolicyCase(
            "short gaps between syllables keep a candidate and its voice adds up",
            listOf(Run(8, 0.9f), Run(4, 0f), Run(8, 0.9f), Run(4, 0f), Run(8, 0.9f), Run(100, 0f)),
            listOf(At(0, TurnSignal.Candidate), At(27, TurnSignal.Confirmed), At(88, ended(TurnEndReason.SILENCE))),
        ),
        PolicyCase(
            "speech then trailing silence ends the turn",
            listOf(Run(30, 0.9f), Run(100, 0f)),
            listOf(At(0, TurnSignal.Candidate), At(19, TurnSignal.Confirmed), At(86, ended(TurnEndReason.SILENCE))),
        ),
        PolicyCase(
            "hysteresis keeps speech going between the thresholds",
            listOf(Run(30, 0.9f), Run(40, 0.4f), Run(100, 0f)),
            listOf(At(0, TurnSignal.Candidate), At(19, TurnSignal.Confirmed), At(126, ended(TurnEndReason.SILENCE))),
        ),
        PolicyCase(
            "a pause shorter than the trailing silence does not end the turn",
            listOf(Run(30, 0.9f), Run(30, 0f), Run(30, 0.9f), Run(100, 0f)),
            listOf(At(0, TurnSignal.Candidate), At(19, TurnSignal.Confirmed), At(146, ended(TurnEndReason.SILENCE))),
        ),
        PolicyCase(
            "below the enter threshold nothing starts",
            listOf(Run(200, 0.45f)),
            emptyList(),
        ),
        PolicyCase(
            "the hard cap ends a turn that never pauses",
            listOf(Run(1_300, 0.9f)),
            listOf(
                At(0, TurnSignal.Candidate),
                At(19, TurnSignal.Confirmed),
                At(1_249, ended(TurnEndReason.HARD_CAP)),
                At(1_250, TurnSignal.Candidate),
                At(1_269, TurnSignal.Confirmed),
            ),
        ),
        PolicyCase(
            "the echo guard ignores moderate speech while the app speaks",
            listOf(Run(200, 0.7f)),
            emptyList(),
            outputActive = true,
        ),
        PolicyCase(
            "the echo guard drops a candidate under its raised exit threshold",
            listOf(Run(10, 0.9f), Run(10, 0.5f)),
            listOf(At(0, TurnSignal.Candidate), At(16, abandoned(TurnDiscardReason.NO_ONSET))),
            outputActive = true,
        ),
        PolicyCase(
            "barge-in waits for its sustain while the app speaks",
            listOf(Run(40, 0.9f)),
            listOf(At(0, TurnSignal.Candidate), At(31, TurnSignal.Confirmed)),
            outputActive = true,
            policy = EndOfTurnPolicy(bargeInSustain = 500.milliseconds),
        ),
        PolicyCase(
            "without output the barge-in sustain does not apply",
            listOf(Run(40, 0.9f)),
            listOf(At(0, TurnSignal.Candidate), At(19, TurnSignal.Confirmed)),
            policy = EndOfTurnPolicy(bargeInSustain = 500.milliseconds),
        ),
    )

    @Test
    fun everyPolicyRowHolds() {
        cases.forEach { case -> assertEquals(case.expected, replay(case), case.name) }
    }

    @Test
    fun commitEndsACandidateOrSpeechAndIgnoresQuiet() {
        val tracker = TurnTracker(EndOfTurnPolicy(), VadSignals.format, hop)
        assertEquals(TurnSignal.None, tracker.commit())
        tracker.onHop(HopObservation(SpeechProbability(0.9f), SamplePosition(256), false))
        assertEquals(ended(TurnEndReason.COMMITTED), tracker.commit())
        assertEquals(false, tracker.recording)
        assertEquals(TurnSignal.None, tracker.commit())
    }

    @Test
    fun voicedAudioIsCountedForTheReport() {
        val case = PolicyCase("", listOf(Run(30, 0.9f), Run(10, 0.2f), Run(10, 0.9f), Run(100, 0f)), emptyList())
        val tracker = TurnTracker(case.policy, VadSignals.format, hop)
        case.runs.flatMap { run -> List(run.hops) { run.probability } }.forEachIndexed { index, probability ->
            tracker.onHop(HopObservation(SpeechProbability(probability), SamplePosition((index + 1L) * hop.samples), false))
        }
        assertEquals(40L * hop.samples, tracker.voicedSamples)
    }
}

class CaptureRecorderTest {
    @Test
    fun aTurnStartsWithThePreRollThenItsOwnAudio() {
        val recorder = CaptureRecorder(preRoll = 4, limit = 100)
        recorder.remember(shortArrayOf(1, 2, 3, 4, 5, 6))
        recorder.begin()
        recorder.record(shortArrayOf(7, 8))
        assertContentEquals(shortArrayOf(3, 4, 5, 6, 7, 8), recorder.finish())
    }

    @Test
    fun aShortPreRollKeepsWhatThereIs() {
        val recorder = CaptureRecorder(preRoll = 4, limit = 100)
        recorder.remember(shortArrayOf(1))
        recorder.begin()
        assertContentEquals(shortArrayOf(1), recorder.finish())
    }

    @Test
    fun aTurnIsBounded() {
        val recorder = CaptureRecorder(preRoll = 2, limit = 5)
        recorder.remember(shortArrayOf(1, 2))
        recorder.begin()
        recorder.record(ShortArray(10) { 9 })
        assertEquals(5, recorder.finish().size)
    }

    @Test
    fun discardAndWipeForgetTheTurnAndPreRoll() {
        val recorder = CaptureRecorder(preRoll = 2, limit = 10)
        recorder.remember(shortArrayOf(1, 2))
        recorder.begin()
        recorder.discard()
        assertEquals(0, recorder.finish().size)
        recorder.wipe()
        recorder.begin()
        assertEquals(0, recorder.finish().size)
    }
}

class HopSplitterTest {
    @Test
    fun framesBecomeContiguousHopsWithTheirEndPositions() {
        val splitter = HopSplitter(DetectorHop(4))
        val first = splitter.split(AudioFrame(ShortArray(6) { it.toShort() }, SamplePosition(0)))
        val second = splitter.split(AudioFrame(ShortArray(6) { (6 + it).toShort() }, SamplePosition(6)))
        val hops = first + second
        assertEquals(listOf(4L, 8L, 12L), hops.map { it.end.value })
        assertContentEquals(shortArrayOf(4, 5, 6, 7), hops[1].samples)
    }

    @Test
    fun aGapDropsThePartialHop() {
        val splitter = HopSplitter(DetectorHop(4))
        splitter.split(AudioFrame(ShortArray(2), SamplePosition(0)))
        val hops = splitter.split(AudioFrame(ShortArray(4) { 1 }, SamplePosition(100)))
        assertEquals(listOf(104L), hops.map { it.end.value })
        assertContentEquals(shortArrayOf(1, 1, 1, 1), hops.single().samples)
    }
}
