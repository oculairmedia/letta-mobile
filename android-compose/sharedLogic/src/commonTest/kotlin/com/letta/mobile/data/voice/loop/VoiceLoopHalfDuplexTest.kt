package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.SpeechStopReason
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The loop where the microphone is not echo-cancelled (half-duplex), and the hold rule for answer text. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopHalfDuplexTest {
    private val halfDuplex = VoiceLoopConfig(voiceInterrupts = false)

    @Test
    fun speechReportedOverTheOutputNeverStopsItAndAPressGoesStraightToListening() = runTest {
        val h = LoopHarness(this, halfDuplex)
        h.speaking()
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        assertEquals(true, h.ear.outputHints.last(), "the ear knows output is active before the mouth plays")
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        assertEquals(0, h.mouth.stops)

        h.listening()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(1, h.mouth.stops)
        assertTrue(h.ear.listening, "voice mode is still on and listening")
        assertEquals(1, h.ear.opens(), "the microphone stayed open throughout")
        assertEquals(listOf(SpeechStopReason.BUTTON), h.stopped)
        val log = h.log.entries
        val stoppedAt = log.indexOf("mouth:stop")
        assertTrue(stoppedAt in 0 until log.lastIndexOf("ear:output=false"), "output ends for the ear only after the stop: $log")
    }

    @Test
    fun withInterruptingBySpeakingOnSpeechOverTheOutputStillBargesIn() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(1, h.mouth.stops)
        assertEquals(listOf(SpeechStopReason.BARGE_IN), h.stopped)
    }

    @Test
    fun aFinishedAnswerStartsWhenTheShortSoundsTurnClosesNotWhenItsTranscriptArrives() = runTest {
        listOf(VoiceLoopConfig(), halfDuplex).forEach { config ->
            val h = LoopHarness(this, config)
            h.listening()
            h.turn()
            h.ear.hear(VoiceEarEvent.SpeechStarted)
            h.settle()
            h.replyText(VoiceText("It is noon. "))
            h.reply(VoiceReplyEvent.Completed)
            assertTrue(h.mouth.spokenTexts.isEmpty(), "held while the turn is open")

            h.ear.hear(VoiceEarEvent.SpeechEnded)
            h.settle()
            assertEquals(listOf("It is noon."), h.mouth.spokenTexts, "released the moment the turn closed")
            assertEquals(VoicePhase.SPEAKING, h.state.phase)

            h.ear.hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText.Empty)))
            h.settle()
            assertEquals(0, h.mouth.stops, "an empty transcript leaves the answer playing")
            assertEquals(VoicePhase.SPEAKING, h.state.phase)
            h.loop.close()
        }
    }
}
