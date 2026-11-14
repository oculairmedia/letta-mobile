package com.letta.mobile.data.voice.vad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EnergySpeechDetectorTest {
    private val signals = VadSignals()

    private fun detector() = EnergySpeechDetector(VadSignals.format)

    /** The probabilities of [tail] after [lead] has trained the detector. */
    private fun after(lead: ShortArray, tail: ShortArray): List<Float> {
        val detector = detector()
        detector.probabilities(lead)
        return detector.probabilities(tail)
    }

    @Test
    fun hopIsSixteenMillisecondsAtSixteenKilohertz() {
        assertEquals(256, detector().hop.samples)
    }

    @Test
    fun silenceIsNeverSpeech() {
        assertTrue(detector().probabilities(signals.silence(2.0.seconds)).all { it == 0f })
    }

    @Test
    fun aQuietRoomIsNotSpeech() {
        val probabilities = detector().probabilities(signals.noise(3.0.seconds, Amplitude(300)))
        assertTrue(probabilities.all { it < 0.35f }, "max ${probabilities.max()}")
    }

    @Test
    fun aVoicedToneOverTheRoomIsSpeech() {
        val probabilities = after(signals.noise(1.0.seconds, VadSignals.BED), signals.tone(0.5.seconds, Pitch(220.0, Amplitude(3_000))))
        assertTrue(probabilities.all { it >= 0.5f }, "min ${probabilities.min()}")
    }

    @Test
    fun loudWhiteNoiseIsHeardAsNoiseNotSpeech() {
        val probabilities = after(signals.noise(1.0.seconds, VadSignals.BED), signals.noise(0.5.seconds, Amplitude(6_000)))
        assertTrue(probabilities.all { it < 0.35f }, "max ${probabilities.max()}")
    }

    @Test
    fun speechLikeSyllablesStayAboveTheExitThreshold() {
        val probabilities = after(signals.noise(1.0.seconds, VadSignals.BED), signals.speechLike(1.0.seconds, Amplitude(4_000)))
        val above = probabilities.count { it >= 0.35f }.toDouble() / probabilities.size
        assertTrue(above > 0.85, "share above exit $above")
        // The troughs between syllables are shorter than the gap the turn tracker bridges before
        // an onset (96 ms, 6 hops), so a voice is never dropped between its syllables.
        val longestGap = probabilities.fold(0 to 0) { (run, longest), p ->
            val next = if (p < 0.35f) run + 1 else 0
            next to maxOf(longest, next)
        }.second
        assertTrue(longestGap <= 6, "longest gap $longestGap hops")
        assertTrue(probabilities.max() >= 0.5f)
    }

    @Test
    fun aNewSteadyNoiseIsLearnedWithinTheSteadyWindow() {
        val probabilities = after(signals.noise(1.0.seconds, VadSignals.BED), signals.tone(4.5.seconds, Pitch(200.0, Amplitude(2_000))))
        val settled = probabilities.drop(probabilities.size * 2 / 3)
        assertTrue(settled.all { it == 0f }, "max after window ${settled.max()}")
    }

    @Test
    fun aSoundBelowTheMinimumSpeechLevelIsNeverSpeech() {
        val probabilities = after(signals.silence(1.0.seconds), signals.tone(0.5.seconds, Pitch(220.0, Amplitude(80))))
        assertTrue(probabilities.all { it == 0f }, "max ${probabilities.max()}")
    }

    @Test
    fun theFloorStartsAtTheRoomsTypicalLevel() {
        val detector = detector()
        detector.probabilities(signals.rumble(0.5.seconds, Amplitude(1_000)))
        val floor = checkNotNull(detector.noiseFloorDb)
        assertTrue(floor > -40.0 && floor < -25.0, "floor $floor")
    }

    @Test
    fun sensitivityMovesTheMinimumLevelAndTheGate() {
        val low = EnergyDetectorConfig.forSensitivity(VadSensitivity.LOW)
        val high = EnergyDetectorConfig.forSensitivity(VadSensitivity.HIGH)
        val normal = EnergyDetectorConfig.forSensitivity(VadSensitivity.NORMAL)
        assertTrue(low.minSpeechDb > normal.minSpeechDb && normal.minSpeechDb > high.minSpeechDb)
        assertTrue(low.snrGateDb > normal.snrGateDb && normal.snrGateDb > high.snrGateDb)
    }

    @Test
    fun nothingIsSpeechWhilePlaybackCalibratesThenOnlyAVoiceAboveTheEcho() {
        val detector = detector()
        detector.probabilities(signals.rumble(1.0.seconds, VadSignals.ROOM))
        detector.setOutputActive(true)
        val echo = detector.probabilities(signals.speechLike(2.0.seconds, Amplitude(3_000)))
        assertTrue(echo.all { it < 0.8f }, "echo max ${echo.max()}")
        val voice = detector.probabilities(signals.tone(0.3.seconds, Pitch(180.0, Amplitude(28_000))))
        assertTrue(voice.count { it >= 0.8f } > voice.size / 2, "voice ${voice.joinToString()}")
        detector.setOutputActive(false)
        assertTrue(detector.probabilities(signals.speechLike(0.5.seconds, Amplitude(3_000))).max() >= 0.5f)
    }

    @Test
    fun resetForgetsTheFloor() {
        val detector = detector()
        detector.probabilities(signals.tone(2.0.seconds, Pitch(200.0, Amplitude(2_000))))
        detector.reset()
        detector.probabilities(signals.noise(0.5.seconds, VadSignals.BED))
        assertTrue(detector.probabilities(signals.tone(0.3.seconds, Pitch(200.0, Amplitude(2_000)))).all { it >= 0.5f })
    }
}
