package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.InterruptStatus
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

/**
 * The ear closes a turn (A) and hands it to transcription; the person starts speaking again (B)
 * before A's transcript is back. Through the real loop and its machine, with speaking interrupting
 * replies on and off: A's transcript is still sent, once, and B is sent after it as a follow-up; no
 * reply is cancelled and nothing is stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopTranscriptOrderTest {
    @Test
    fun speechStartingBeforeTheLastTranscriptIsAFollowUpWhenSpeakingInterrupts() = followUpBeforeTranscript(voiceInterrupts = true)

    @Test
    fun speechStartingBeforeTheLastTranscriptIsAFollowUpWhenOnlyAPressInterrupts() = followUpBeforeTranscript(voiceInterrupts = false)

    private fun followUpBeforeTranscript(voiceInterrupts: Boolean) = runTest {
        val h = LoopHarness(this, VoiceLoopConfig(voiceInterrupts = voiceInterrupts))
        h.listening()

        // A: spoken and closed; its audio is being transcribed.
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.ear.hear(VoiceEarEvent.SpeechEnded)
        h.settle()
        // B starts before A's transcript arrives.
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        h.ear.hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText(FIRST))))
        h.settle()
        assertEquals(listOf(FIRST), h.responder.replies.map { it.utterance.text.value }, "A is sent while B is still spoken")

        h.ear.hear(VoiceEarEvent.SpeechEnded)
        h.ear.hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText(SECOND))))
        h.settle()
        val (first, second) = h.responder.replies
        assertEquals(listOf(FIRST, SECOND), h.responder.replies.map { it.utterance.text.value }, "each sent once, in order")
        assertEquals(0, first.cancels + second.cancels, "B is a follow-up: it cancels nothing")
        assertEquals(0, h.mouth.stops)
        assertEquals(InterruptStatus.NONE, h.state.interrupt)

        first.text("It is noon. ")
        first.send(VoiceReplyEvent.Completed)
        second.text("Tuesday. ")
        second.send(VoiceReplyEvent.Completed)
        h.settle()
        h.finishSpeech()
        h.finishSpeech()
        assertEquals(listOf("It is noon.", "Tuesday."), h.mouth.spokenTexts, "both answers are spoken, A's first")
        assertEquals(VoicePhase.LISTENING, h.state.phase)
    }

    private companion object {
        const val FIRST = "what time is it"
        const val SECOND = "and the date"
    }
}
