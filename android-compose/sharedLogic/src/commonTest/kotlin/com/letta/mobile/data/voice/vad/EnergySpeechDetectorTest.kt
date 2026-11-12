package com.letta.mobile.data.voice.vad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertTrue(detector().probabilities(signals.silence(2.0)).all { it == 0f })
    }

    @Test
    fun aQuietRoomIsNotSpeech() {
        val probabilities = detector().probabilities(signals.noise(3.0, 300))
        assertTrue(probabilities.all { it < 0.35f }, "max ${probabilities.max()}")
    }

    @Test
    fun aVoicedToneOverTheRoomIsSpeech() {
        val probabilities = after(signals.noise(1.0, VadSignals.BED), signals.tone(0.5, 220.0, 3_000))
        assertTrue(probabilities.all { it >= 0.5f }, "min ${probabilities.min()}")
    }

    @Test
    fun loudWhiteNoiseIsHeardAsNoiseNotSpeech() {
        val probabilities = after(signals.noise(1.0, VadSignals.BED), signals.noise(0.5, 6_000))
        assertTrue(probabilities.all { it < 0.35f }, "max ${probabilities.max()}")
    }

    @Test
    fun speechLikeSyllablesStayAboveTheExitThreshold() {
        val probabilities = after(signals.noise(1.0, VadSignals.BED), signals.speechLike(1.0, 4_000))
        val above = probabilities.count { it >= 0.35f }.toDouble() / probabilities.size
        assertTrue(above > 0.9, "share above exit $above")
        assertTrue(probabilities.max() >= 0.5f)
    }

    @Test
    fun aNewSteadyNoiseIsLearnedWithinTheFloorWindow() {
        val probabilities = after(signals.noise(1.0, VadSignals.BED), signals.tone(3.0, 200.0, 2_000))
        val settled = probabilities.drop(probabilities.size * 2 / 3)
        assertTrue(settled.all { it == 0f }, "max after window ${settled.max()}")
    }

    @Test
    fun resetForgetsTheFloor() {
        val detector = detector()
        detector.probabilities(signals.tone(2.0, 200.0, 2_000))
        detector.reset()
        detector.probabilities(signals.noise(0.5, VadSignals.BED))
        assertTrue(detector.probabilities(signals.tone(0.3, 200.0, 2_000)).all { it >= 0.5f })
    }
}
