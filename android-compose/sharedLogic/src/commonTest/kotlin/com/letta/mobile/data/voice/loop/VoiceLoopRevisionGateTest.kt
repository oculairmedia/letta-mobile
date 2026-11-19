package com.letta.mobile.data.voice.loop

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

/** The revision gate through the running loop: offered words are sent early, their answer plays only once confirmed. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopRevisionGateTest {
    private suspend fun LoopHarness.offer(words: String) {
        ear.hear(VoiceEarEvent.TentativeEndOfTurn(VoiceUtterance(VoiceText(words))))
        settle()
    }

    private suspend fun LoopHarness.end(words: String) {
        ear.hear(VoiceEarEvent.SpeechEnded)
        ear.hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText(words))))
        settle()
    }

    @Test
    fun theAgentStartsOnTheOfferAndItsAnswerPlaysOnceTheTurnConfirmsIt() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.offer("what time is it")
        assertEquals(1, h.responder.replies.size, "sent before the turn is over")
        assertTrue(h.responder.last.collected)
        h.replyText(VoiceText("It is noon. "))
        assertEquals(emptyList(), h.mouth.spokenTexts, "nothing plays while the person may still go on")
        assertTrue(h.ear.listening, "the microphone stays open to hear the person go on")

        h.end("What time is it?")
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts)
        assertEquals(1, h.responder.replies.size, "the confirmed words are not sent again")
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
    }

    @Test
    fun goingOnSpeakingStopsTheStaleAnswerAndSendsTheWholeTurn() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.offer("book a table")
        val stale = h.responder.last
        h.replyText(VoiceText("Sure, for when? "))

        h.ear.hear(VoiceEarEvent.TentativeWithdrawn)
        h.settle()
        assertEquals(1, stale.cancels, "the stale answer's run is asked to stop")
        stale.send(VoiceReplyEvent.Text(VoiceText("Anything else? ")))
        h.settle()
        assertEquals(emptyList(), h.mouth.spokenTexts, "nothing of the stale answer ever plays")
        assertTrue(h.skipped.contains("REVISED:1"))

        h.end("book a table for two")
        assertEquals("book a table for two", h.responder.last.utterance.text.value)
        h.replyText(VoiceText("Booked. "))
        assertEquals(listOf("Booked."), h.mouth.spokenTexts)
    }
}
