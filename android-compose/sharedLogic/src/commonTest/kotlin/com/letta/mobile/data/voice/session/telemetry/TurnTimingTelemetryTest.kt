package com.letta.mobile.data.voice.session.telemetry

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** `turn.timing`: every stage of one turn, from the end of the person's speech to the answer's first audio. */
class TurnTimingTelemetryTest {
    private val clock = TestTimeSource()
    private val events = mutableListOf<Pair<String, List<VoiceTelemetryField>>>()
    private val telemetry = VoiceTelemetry(clock) { _, name, fields -> events += name to fields }
    private val ear = VoiceEarTelemetry(telemetry)
    private val session = TelemetryVoiceChatSession(StubSession, telemetry)

    private fun report(trailing: Duration) =
        EarTurnReport(voiced = 1_200.milliseconds, peakDb = -20.0, floorDb = -60.0, trailingSilence = trailing)

    private fun timings(): List<Map<String, VoiceTelemetryValue>> = events.filter { it.first == "turn.timing" }.map { it.second.toMap() }

    private suspend fun firstAudioPlays() {
        val played = flowOf(VoiceMouthEvent.Started(SpeechSegmentId(1)), VoiceMouthEvent.Started(SpeechSegmentId(2)))
        TelemetryMouth(StubMouth(played), telemetry).events.toList()
    }

    private fun sentenceRequested() =
        TelemetryMouth(StubMouth(flowOf()), telemetry).speak(SpeechSegment(SpeechSegmentId(1), VoiceText("A private reply sentence.")))

    @Test
    fun aServerEarTurnReportsEveryStage() = runTest {
        telemetry.started()
        ear.turnClosed(report(trailing = 900.milliseconds), TurnEndReason.SILENCE)
        clock += 300.milliseconds
        ear.transcriptAccepted(characters = 18)
        session.send(VoiceText("my private words"))
        clock += 1_200.milliseconds
        sentenceRequested()
        clock += 250.milliseconds
        firstAudioPlays()

        val timing = timings().single()
        assertEquals(VoiceTelemetryValue.Numeric(900L), timing["decisionMs"])
        assertEquals(VoiceTelemetryValue.Numeric(300L), timing["sttMs"])
        assertEquals(VoiceTelemetryValue.Numeric(1_200L), timing["agentFirstSentenceMs"])
        assertEquals(VoiceTelemetryValue.Numeric(250L), timing["ttsFirstAudioMs"])
        assertEquals(VoiceTelemetryValue.Numeric(2_650L), timing["e2eMs"], "from the estimated end of speech")
    }

    @Test
    fun timingCarriesDurationsOnly() = runTest {
        telemetry.started()
        ear.turnClosed(report(trailing = 600.milliseconds), TurnEndReason.SILENCE)
        ear.transcriptAccepted(characters = 16)
        session.send(VoiceText("my private words"))
        sentenceRequested()
        firstAudioPlays()

        val fields = events.single { it.first == "turn.timing" }.second
        val names = fields.map { it.first }.toSet()
        assertEquals(setOf("decisionMs", "sttMs", "agentFirstSentenceMs", "ttsFirstAudioMs", "e2eMs", "tMs"), names)
        assertTrue(fields.all { it.second is VoiceTelemetryValue.Numeric }, "numbers only: no text, key or address")
    }

    @Test
    fun aDroppedTurnOrAStopReportsNothing() = runTest {
        ear.turnClosed(report(trailing = 900.milliseconds), TurnEndReason.SILENCE)
        ear.turnDiscarded(report(trailing = 900.milliseconds), TurnDiscardReason.STOCK_PHRASE)
        firstAudioPlays()
        ear.turnClosed(report(trailing = 900.milliseconds), TurnEndReason.SILENCE)
        telemetry.stopped("stopRequested")
        firstAudioPlays()
        assertEquals(emptyList(), timings())
    }

    @Test
    fun stagesThatWereNotSeenAreMissingAndOnlyTheFirstAudioCounts() = runTest {
        ear.turnClosed(report(trailing = 700.milliseconds), TurnEndReason.SILENCE)
        clock += 100.milliseconds
        firstAudioPlays()
        firstAudioPlays()
        val timing = timings().single()
        assertEquals(VoiceTelemetryValue.Missing, timing["sttMs"])
        assertEquals(VoiceTelemetryValue.Missing, timing["agentFirstSentenceMs"])
        assertEquals(VoiceTelemetryValue.Missing, timing["ttsFirstAudioMs"])
        assertEquals(VoiceTelemetryValue.Numeric(800L), timing["e2eMs"])
    }

    @Test
    fun theLocalEarTimesFromTheEndOfSpeechItReports() = runTest {
        LocalEarTelemetry(telemetry).heard(characters = 12, sinceEndOfSpeech = 250.milliseconds)
        session.send(VoiceText("my private words"))
        clock += 900.milliseconds
        sentenceRequested()
        clock += 100.milliseconds
        firstAudioPlays()
        val timing = timings().single()
        assertEquals(VoiceTelemetryValue.Missing, timing["decisionMs"], "the recogniser decides with its transcript")
        assertEquals(VoiceTelemetryValue.Numeric(250L), timing["sttMs"])
        assertEquals(VoiceTelemetryValue.Numeric(1_250L), timing["e2eMs"])
    }

    private object StubSession : VoiceChatSession {
        override val state: StateFlow<VoiceChatState> = MutableStateFlow(VoiceChatState())

        override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

        override fun stopRun() = Unit
    }

    private class StubMouth(override val events: Flow<VoiceMouthEvent>) : VoiceMouth {
        override fun speak(segment: SpeechSegment) = Unit

        override fun flush() = Unit

        override suspend fun stop() = Unit
    }
}
