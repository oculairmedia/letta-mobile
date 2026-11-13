package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.SamplePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real energy detector and turn tracker together over synthetic 16 kHz audio: what opens a turn
 * ([TurnSignal.Confirmed], the barge-in signal) and what never does.
 */
class VadRobustnessTest {
    private val signals = VadSignals()

    /** Every signal (other than None) the detector and tracker produce for [audio], with output playing during [playing]. */
    private fun signalsFor(
        audio: ShortArray,
        sensitivity: VadSensitivity = VadSensitivity.NORMAL,
        playing: (Int) -> Boolean = { false },
    ): List<TurnSignal> {
        val detector = EnergySpeechDetector(VadSignals.format, EnergyDetectorConfig.forSensitivity(sensitivity))
        val tracker = TurnTracker(EndOfTurnPolicy(), VadSignals.format, detector.hop)
        val size = detector.hop.samples
        return (0 until audio.size / size).mapNotNull { index ->
            val active = playing(index * size)
            detector.setOutputActive(active)
            val probability = detector.probability(audio.copyOfRange(index * size, (index + 1) * size))
            tracker.onHop(HopObservation(probability, SamplePosition((index + 1L) * size), active))
                .takeUnless { it == TurnSignal.None }
        }
    }

    private fun turnsIn(found: List<TurnSignal>): Int = found.count { it == TurnSignal.Confirmed }

    private fun join(vararg parts: ShortArray): ShortArray = parts.reduce { acc, part -> acc + part }

    @Test
    fun steadyNoiseAtSeveralLevelsNeverOpensATurn() {
        listOf(100, 400, 1_500, 5_000).forEach { amplitude ->
            listOf(signals.noise(6.0, amplitude), signals.rumble(6.0, amplitude)).forEach { noise ->
                VadSensitivity.entries.forEach { sensitivity ->
                    assertEquals(0, turnsIn(signalsFor(noise, sensitivity)), "amplitude $amplitude at $sensitivity")
                }
            }
        }
    }

    @Test
    fun noiseWhoseLevelWandersNeverOpensATurn() {
        listOf(300, 1_500).forEach { amplitude ->
            assertEquals(0, turnsIn(signalsFor(signals.wanderingRumble(8.0, amplitude))), "amplitude $amplitude")
        }
    }

    @Test
    fun clicksAndKeyboardBurstsNeverOpenATurn() {
        val room = signals.rumble(1.0, 150)
        val typing = join(room, signals.clicks(5.0, every = 0.18, click = 0.03, amplitude = 12_000))
        val knocks = join(room, signals.clicks(5.0, every = 0.7, click = 0.12, amplitude = 20_000))
        assertEquals(0, turnsIn(signalsFor(typing)), "typing")
        assertEquals(0, turnsIn(signalsFor(knocks)), "knocks")
        val dropped = signalsFor(typing).filterIsInstance<TurnSignal.Abandoned>()
        assertTrue(dropped.all { it.reason == TurnDiscardReason.NO_ONSET }, "$dropped")
    }

    @Test
    fun speechAboveTheRoomOpensOneTurnThatEnds() {
        val found = signalsFor(join(signals.rumble(1.0, 150), signals.speechLike(1.5, 4_000), signals.rumble(1.5, 150)))
        assertEquals(1, turnsIn(found))
        assertEquals(TurnSignal.Ended(TurnEndReason.SILENCE), found.last())
    }

    @Test
    fun loudRoomNoiseThenSpeechOpensOneTurnForTheSpeech() {
        val found = signalsFor(join(signals.rumble(4.0, 1_200), signals.speechLike(1.5, 12_000), signals.rumble(1.5, 1_200)))
        assertEquals(1, turnsIn(found))
        assertEquals(TurnSignal.Ended(TurnEndReason.SILENCE), found.last())
    }

    @Test
    fun speechWithAThreeHundredMillisecondPauseStaysOneTurn() {
        val found = signalsFor(
            join(
                signals.rumble(1.0, 150),
                signals.speechLike(1.0, 4_000),
                signals.rumble(0.3, 150),
                signals.speechLike(1.0, 4_000),
                signals.rumble(1.5, 150),
            ),
        )
        assertEquals(1, turnsIn(found))
        assertEquals(1, found.count { it is TurnSignal.Ended })
    }

    @Test
    fun aShortBurstOfVoiceIsDroppedNotTurnedIntoATurn() {
        val found = signalsFor(join(signals.rumble(1.0, 150), signals.tone(0.25, 180.0, 4_000), signals.rumble(1.5, 150)))
        assertEquals(0, turnsIn(found))
        assertEquals(listOf(TurnSignal.Abandoned(TurnDiscardReason.TOO_LITTLE_VOICE)), found.filterIsInstance<TurnSignal.Abandoned>())
    }

    @Test
    fun theAppsOwnEchoNeverOpensATurnButAClearlyLouderVoiceDoes() {
        val rate = 16_000
        val room = signals.rumble(1.0, 150)
        val echo = signals.speechLike(4.0, 3_000)
        val playingFrom = room.size
        val echoOnly = signalsFor(join(room, echo)) { it >= playingFrom }
        assertEquals(0, turnsIn(echoOnly), "echo")

        val barge = echo.copyOf().also { mixed ->
            val voice = signals.speechLike(1.5, 26_000)
            for (index in voice.indices) {
                val at = 2 * rate + index
                mixed[at] = (mixed[at] + voice[index]).coerceIn(-32_768, 32_767).toShort()
            }
        }
        val bargeIn = signalsFor(join(room, barge)) { it >= playingFrom }
        assertEquals(1, turnsIn(bargeIn), "barge-in")
    }
}
