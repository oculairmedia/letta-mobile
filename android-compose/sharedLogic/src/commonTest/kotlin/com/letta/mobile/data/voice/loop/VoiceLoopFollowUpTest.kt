package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The loop with more than one utterance in flight, and failures it goes on after: follow-ups while
 * an answer is awaited, barge-in that sends only once the stop has settled, and failed answers or
 * speech reported while voice mode keeps listening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopFollowUpTest {
    /**
     * Speech while thinking used to interrupt the awaited answer; it now starts a follow-up and
     * interrupts nothing (the answer is still awaited and nothing is cancelled).
     */
    @Test
    fun speechWhileThinkingInterruptsNothing() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.THINKING, h.state.phase)
        assertEquals(0, h.responder.last.cancels)
        assertEquals(0, h.mouth.stops)
        assertEquals(InterruptStatus.NONE, h.state.interrupt)
    }

    @Test
    fun twoUtterancesWhileThinkingAreBothSpokenInOrder() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn(VoiceText("what time is it"))
        h.turn(VoiceText("and the date"))
        val (first, second) = h.responder.replies
        assertTrue(first.collected && second.collected)
        second.text("Tuesday. ")
        h.settle()
        assertEquals(emptyList(), h.mouth.spokenTexts)
        first.text("It is noon. ")
        first.send(VoiceReplyEvent.Completed)
        second.send(VoiceReplyEvent.Completed)
        h.settle()
        h.finishSpeech()
        h.finishSpeech()
        assertEquals(listOf("It is noon.", "Tuesday."), h.mouth.spokenTexts)
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(0, first.cancels + second.cancels)
    }

    @Test
    fun bargeInSendsTheNewUtteranceOnlyOnceTheStopHasSettled() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        val first = h.responder.last
        val gate = CompletableDeferred<Unit>()
        first.cancelGate = gate
        h.turn(VoiceText("never mind"))
        val second = h.responder.last
        assertEquals(1, first.cancels)
        assertFalse(second.collected)
        assertEquals(listOf("BARGE_IN:1"), h.skipped)
        gate.complete(Unit)
        h.settle()
        assertTrue(second.collected)
    }

    @Test
    fun aStopThatNeverSettlesDelaysTheNewUtteranceOnlyByTheReleaseBudget() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.responder.last.cancelGate = CompletableDeferred()
        h.turn(VoiceText("never mind"))
        val second = h.responder.last
        assertFalse(second.collected)
        advanceTimeBy(VoiceLoopConfig().releaseTimeout + 1.seconds)
        h.settle()
        assertTrue(second.collected)
        assertEquals(InterruptStatus.UNRESPONSIVE, h.state.interrupt)
    }

    /** A failed answer used to stop the loop (FAILED); it is now reported and the loop listens on, retryable. */
    @Test
    fun responderFailureIsReportedWithItsCodeAndTheLoopListensOn() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.reply(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.RUN_FAILED, VoicePort.RESPONDER)))
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertNull(h.state.failure)
        assertEquals(VoiceErrorCode.RUN_FAILED, h.state.turnFailure?.code)
        assertEquals(VoiceUserAction.RETRY, h.state.turnFailure?.action)
        assertTrue(h.state.replyRetryable)
        assertTrue(h.ear.listening)
        h.loop.retryReply()
        h.settle()
        assertEquals(2, h.responder.replies.size)
        assertEquals("what time is it", h.responder.last.utterance.text.value)
        assertEquals(VoicePhase.THINKING, h.state.phase)
    }

    @Test
    fun undeliveredUtteranceKeepsVoiceModeListening() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.reply(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.NOT_DELIVERED, VoicePort.RESPONDER)))
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertTrue(h.state.microphoneOpen)
        h.turn(VoiceText("hello again"))
        assertEquals(VoicePhase.THINKING, h.state.phase)
        assertNull(h.state.turnFailure)
    }

    /** Used to fail the loop once the speech ended; the speech now ends and the loop listens on. */
    @Test
    fun responderFailureAfterSomeSpeechLetsTheSpeechFinishAndListensOn() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.reply(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.RUN_FAILED, VoicePort.RESPONDER)))
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        h.finishSpeech()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(VoiceErrorCode.RUN_FAILED, h.state.turnFailure?.code)
    }

    /** Used to fail the loop; the timed-out answer is now stopped and reported, and the loop listens on. */
    @Test
    fun thinkingTimeoutStopsTheAnswerAndListensOn() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        advanceTimeBy(VoiceLoopConfig().thinkingTimeout + 1.seconds)
        h.settle()
        assertEquals(VoiceErrorCode.TIMED_OUT, h.state.turnFailure?.code)
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(1, h.responder.last.cancels)
    }

    @Test
    fun aFailedSegmentOfSpeechIsSkippedAndTheNextAnswerIsSpoken() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        val error = VoiceError(VoiceErrorCode.SYNTHESIS_FAILED, VoicePort.MOUTH)
        h.mouth.emit(VoiceMouthEvent.Failed(error, h.mouth.queued.single().id))
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(error, h.state.turnFailure)
        assertEquals(listOf("MOUTH_FAILED:1"), h.skipped)
        h.turn(VoiceText("try again"))
        h.replyText(VoiceText("Sure. "))
        assertEquals(listOf("It is noon.", "Sure."), h.mouth.spokenTexts)
    }
}
