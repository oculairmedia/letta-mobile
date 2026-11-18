package com.letta.mobile.data.voice.session.telemetry

import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** `speech.firstAudio`: from the end of the person's turn to the answer's first played audio. */
class FirstAudioTelemetryTest {
    private val clock = TestTimeSource()
    private val events = mutableListOf<Pair<String, List<VoiceTelemetryField>>>()
    private val telemetry = VoiceTelemetry(clock) { _, name, fields -> events += name to fields }
    private val ear = VoiceEarTelemetry(telemetry)
    private val report = EarTurnReport(voiced = 900.milliseconds, peakDb = -20.0, floorDb = -60.0)

    private fun firstAudio(): List<Map<String, VoiceTelemetryValue>> =
        events.filter { it.first == "speech.firstAudio" }.map { it.second.toMap() }

    /** Plays the given mouth events through the telemetry wrapper. */
    private suspend fun mouthPlays(vararg played: VoiceMouthEvent) {
        val mouth = TelemetryMouth(StubMouth(flowOf(*played)), telemetry)
        mouth.events.toList()
    }

    @Test
    fun theFirstAudioAfterATurnReportsTheTimeSinceTheTurnEnded() = runTest {
        telemetry.started()
        ear.turnClosed(report, TurnEndReason.SILENCE)
        clock += 740.milliseconds
        mouthPlays(VoiceMouthEvent.Started(SpeechSegmentId(1)), VoiceMouthEvent.Started(SpeechSegmentId(2)))
        val reported = firstAudio().single()
        assertEquals(VoiceTelemetryValue.Numeric(740L), reported["afterTurnEndMs"], "only the answer's first segment is timed")
        assertTrue(reported.values.none { it is VoiceTelemetryValue.Text }, "a duration only: no text of any kind")
    }

    @Test
    fun aTranscriptThatWasDroppedTimesNothing() = runTest {
        ear.turnClosed(report, TurnEndReason.SILENCE)
        ear.turnDiscarded(report, TurnDiscardReason.STOCK_PHRASE)
        mouthPlays(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        assertEquals(emptyList(), firstAudio())
    }

    @Test
    fun aSoundDroppedBeforeItBecameATurnKeepsTheTurnThatEnded() = runTest {
        ear.turnClosed(report, TurnEndReason.SILENCE)
        clock += 300.milliseconds
        ear.turnDiscarded(report, TurnDiscardReason.NO_ONSET)
        clock += 200.milliseconds
        mouthPlays(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        assertEquals(VoiceTelemetryValue.Numeric(500L), firstAudio().single()["afterTurnEndMs"])
    }

    @Test
    fun theLocalEarTimesFromTheEndOfSpeechItReports() = runTest {
        LocalEarTelemetry(telemetry).heard(characters = 12, sinceEndOfSpeech = 250.milliseconds)
        clock += 400.milliseconds
        mouthPlays(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        assertEquals(VoiceTelemetryValue.Numeric(650L), firstAudio().single()["afterTurnEndMs"])
    }

    @Test
    fun stoppingVoiceModeForgetsTheTurn() = runTest {
        ear.turnClosed(report, TurnEndReason.SILENCE)
        telemetry.stopped("stopRequested")
        mouthPlays(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        assertEquals(emptyList(), firstAudio())
    }

    private class StubMouth(override val events: Flow<VoiceMouthEvent>) : VoiceMouth {
        override fun speak(segment: SpeechSegment) = Unit

        override fun flush() = Unit

        override suspend fun stop() = Unit
    }
}
