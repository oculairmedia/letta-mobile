package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.LateReplyPolicy
import com.letta.mobile.data.voice.VoiceActionDecision
import com.letta.mobile.data.voice.VoiceActionKind
import com.letta.mobile.data.voice.VoiceConversationMode
import com.letta.mobile.data.voice.VoiceDecision
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.activation.KeyDecision
import com.letta.mobile.data.voice.activation.VoiceActivationEvent
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.activation.VoiceActivationSource
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopTest {
    private val singleShot = VoiceLoopConfig(mode = VoiceConversationMode.SINGLE_SHOT)
    private val noBargeIn = VoiceLoopConfig(bargeIn = false)

    @Test
    fun continuousTurnListensThinksSpeaksAndListensAgain() = runTest {
        val h = LoopHarness(this)
        h.listening()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertTrue(h.state.microphoneOpen)
        h.turn(VoiceText("what time is it"))
        assertEquals(VoicePhase.THINKING, h.state.phase)
        assertEquals("what time is it", h.responder.last.utterance.text.value)
        h.replyText(VoiceText("It is noon. And sunny"))
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts)
        h.reply(VoiceReplyEvent.Completed)
        assertEquals(listOf("It is noon.", "And sunny"), h.mouth.spokenTexts)
        h.finishSpeech()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertTrue(h.ear.listening)
    }

    @Test
    fun singleShotGoesIdleAndClosesTheMicrophone() = runTest {
        val h = LoopHarness(this, singleShot)
        h.speaking()
        h.reply(VoiceReplyEvent.Completed)
        h.finishSpeech()
        assertEquals(VoicePhase.IDLE, h.state.phase)
        assertFalse(h.ear.listening)
        assertFalse(h.state.microphoneOpen)
    }

    @Test
    fun replyWithNoTextGoesBackToListening() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.reply(VoiceReplyEvent.Completed)
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertTrue(h.mouth.spokenTexts.isEmpty())
    }

    @Test
    fun bargeInWhileSpeakingStopsMouthInterruptsAnswerAndDropsItsText() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(1, h.mouth.stops)
        assertEquals(1, h.responder.last.cancels)
        assertEquals(InterruptStatus.NONE, h.state.interrupt)
        h.replyText(VoiceText("More words. "))
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts)
    }

    @Test
    fun bargeInWhileThinkingInterruptsTheAnswer() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(1, h.responder.last.cancels)
        assertEquals(0, h.mouth.stops)
    }

    @Test
    fun bargeInWhileListeningOnlyMarksSpeech() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(0, h.mouth.stops)
    }

    @Test
    fun newTurnSpokenOverReplyStartsAFreshGeneration() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        val first = h.responder.last
        h.turn(VoiceText("never mind, what day is it"))
        assertEquals(2, h.responder.replies.size)
        assertEquals(VoicePhase.THINKING, h.state.phase)
        first.text("Stale words. ")
        h.settle()
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts)
        h.replyText(VoiceText("Tuesday. "))
        assertEquals(listOf("It is noon.", "Tuesday."), h.mouth.spokenTexts)
    }

    @Test
    fun unresponsiveInterruptKeepsDroppingTextUntilTheAnswerEnds() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.responder.last.cancelOutcome = VoiceCancelOutcome.UNRESPONSIVE
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(InterruptStatus.UNRESPONSIVE, h.state.interrupt)
        h.replyText(VoiceText("Still talking. "))
        assertTrue(h.mouth.spokenTexts.isEmpty())
        h.reply(VoiceReplyEvent.Completed)
        assertEquals(InterruptStatus.NONE, h.state.interrupt)
    }

    @Test
    fun stopWorksInEveryPhase() = runTest {
        val listening = LoopHarness(this).apply { listening() }
        val thinking = LoopHarness(this).apply { listening(); turn() }
        val speaking = LoopHarness(this).apply { speaking() }
        listOf(listening, thinking, speaking).forEach { h ->
            h.loop.stop()
            h.settle()
            assertEquals(VoicePhase.IDLE, h.state.phase)
            assertFalse(h.ear.listening)
            assertFalse(h.state.microphoneOpen)
        }
        assertEquals(1, thinking.responder.last.cancels)
        assertEquals(1, speaking.mouth.stops)
    }

    @Test
    fun stopFromFailedAndIdleSettlesIdle() = runTest {
        val h = LoopHarness(this)
        h.loop.stop()
        h.settle()
        assertEquals(VoicePhase.IDLE, h.state.phase)
        h.listening()
        h.ear.hear(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, VoicePort.EAR)))
        h.settle()
        h.loop.stop()
        h.settle()
        assertEquals(VoicePhase.IDLE, h.state.phase)
        assertNull(h.state.failure)
    }

    @Test
    fun stopIsReportedOnlyAfterTheEarHasReleasedTheMicrophone() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.ear.releaseDelay = 1.seconds
        h.loop.stop()
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertTrue(h.ear.listening)
        advanceTimeBy(1.seconds + 1.seconds / 10)
        h.settle()
        assertEquals(VoicePhase.IDLE, h.state.phase)
        assertFalse(h.ear.listening)
    }

    @Test
    fun mouthThatWillNotStopBecomesUnresponsive() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.mouth.hangOnStop = true
        h.loop.stop()
        advanceTimeBy(VoiceLoopConfig().releaseTimeout + 1.seconds)
        h.settle()
        assertEquals(VoicePhase.FAILED, h.state.phase)
        assertEquals(VoiceError(VoiceErrorCode.UNRESPONSIVE, VoicePort.MOUTH), h.state.failure)
    }

    @Test
    fun responderFailureFailsWithItsCodeAndSuggestedAction() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.reply(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.RUN_FAILED, VoicePort.RESPONDER)))
        assertEquals(VoicePhase.FAILED, h.state.phase)
        assertEquals(VoiceErrorCode.RUN_FAILED, h.state.failure?.code)
        assertEquals(VoiceUserAction.RETRY, h.state.failure?.action)
        assertFalse(h.ear.listening)
    }

    @Test
    fun responderFailureAfterSomeSpeechIsReportedOnceTheSpeechEnds() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.reply(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.RUN_FAILED, VoicePort.RESPONDER)))
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        h.finishSpeech()
        assertEquals(VoicePhase.FAILED, h.state.phase)
    }

    @Test
    fun thinkingTimeoutFailsAndCancelsTheAnswer() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        advanceTimeBy(VoiceLoopConfig().thinkingTimeout + 1.seconds)
        h.settle()
        assertEquals(VoiceErrorCode.TIMED_OUT, h.state.failure?.code)
        assertEquals(1, h.responder.last.cancels)
    }

    @Test
    fun thinkingTimeoutDoesNotFireOnceSpeaking() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        advanceTimeBy(VoiceLoopConfig().thinkingTimeout + 1.seconds)
        h.settle()
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
    }

    @Test
    fun lateReplyIsSpokenWhenItLandsWhileListening() = runTest {
        val h = LoopHarness(this)
        h.speaking(VoiceText("On it. "))
        h.reply(VoiceReplyEvent.AwaitingLateReply)
        h.finishSpeech()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertTrue(h.state.awaitingLateReply)
        h.replyText(VoiceText("Research done. "))
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        h.reply(VoiceReplyEvent.Completed)
        h.finishSpeech()
        assertEquals(listOf("On it.", "Research done."), h.mouth.spokenTexts)
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertFalse(h.state.awaitingLateReply)
    }

    @Test
    fun lateReplyIsHeldWhileThePersonSpeaksAndSpokenAfterTheNextAnswer() = runTest {
        val h = LoopHarness(this)
        h.speaking(VoiceText("On it. "))
        val delegated = h.responder.last
        h.reply(VoiceReplyEvent.AwaitingLateReply)
        h.finishSpeech()
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        delegated.text("Research done. ")
        h.settle()
        assertEquals(1, h.state.heldLateSegments)
        h.ear.hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("thanks"))))
        h.settle()
        h.reply(VoiceReplyEvent.Completed)
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        assertEquals(listOf("On it.", "Research done."), h.mouth.spokenTexts)
    }

    @Test
    fun discardPolicyNeverSpeaksLateReplies() = runTest {
        val h = LoopHarness(this, VoiceLoopConfig(lateReplies = LateReplyPolicy.DISCARD))
        h.speaking(VoiceText("On it. "))
        h.reply(VoiceReplyEvent.AwaitingLateReply)
        h.finishSpeech()
        h.replyText(VoiceText("Research done. "))
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(listOf("On it."), h.mouth.spokenTexts)
    }

    @Test
    fun conversationChangeDropsLateReplies() = runTest {
        val h = LoopHarness(this)
        h.speaking(VoiceText("On it. "))
        val delegated = h.responder.last
        h.reply(VoiceReplyEvent.AwaitingLateReply)
        h.finishSpeech()
        h.loop.conversationChanged()
        h.settle()
        h.listening()
        delegated.text("Research done. ")
        h.settle()
        assertEquals(listOf("On it."), h.mouth.spokenTexts)
        assertEquals(1, delegated.cancels)
    }

    @Test
    fun withoutBargeInTheMicrophoneIsClosedWhileSpeakingAndMouthStopsBeforeItReopens() = runTest {
        val h = LoopHarness(this, noBargeIn)
        h.speaking()
        assertFalse(h.ear.listening)
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        h.loop.activate(VoiceActivationKind.TAP)
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        val entries = h.log.entries
        assertTrue(entries.lastIndexOf("mouth:stop") < entries.lastIndexOf("ear:open"))
    }

    @Test
    fun earIsToldWhileOutputPlays() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.reply(VoiceReplyEvent.Completed)
        h.finishSpeech()
        assertEquals(listOf(true, false), h.ear.outputHints)
    }

    @Test
    fun failedCaptureCanBeRetriedOnce() = runTest {
        val h = LoopHarness(this)
        h.listening()
        val handle = CapturedAudioHandle(7)
        h.ear.hear(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, VoicePort.EAR), handle))
        h.settle()
        assertEquals(VoicePhase.FAILED, h.state.phase)
        assertEquals(handle, h.state.retryableCapture)
        h.loop.retryCapture()
        h.settle()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
        assertEquals(listOf(handle), h.ear.retried)
        assertNull(h.state.retryableCapture)
    }

    @Test
    fun degradedWakeWordIsANoticeAndActivationStillWorks() = runTest {
        val h = LoopHarness(this)
        val notice = VoiceError(VoiceErrorCode.ASSET_MISSING, VoicePort.ACTIVATION)
        h.loop.bind(source(VoiceActivationEvent.Degraded(VoiceActivationKind.WAKE_WORD, notice)))
        h.settle()
        assertEquals(notice, h.state.activationNotice)
        assertEquals(VoicePhase.IDLE, h.state.phase)
        h.listening()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
    }

    @Test
    fun earAnsweredTurnIsSpokenWithoutTheResponder() = runTest {
        val h = LoopHarness(this)
        h.listening()
        val utterance = VoiceUtterance(VoiceText("hi"), VoiceText("Hello!"), VoiceDecision.AnsweredByEar)
        h.ear.hear(VoiceEarEvent.EndOfTurn(utterance))
        h.settle()
        assertEquals(VoicePhase.SPEAKING, h.state.phase)
        assertTrue(h.responder.replies.isEmpty())
        h.finishSpeech()
        assertEquals(VoicePhase.LISTENING, h.state.phase)
    }

    @Test
    fun earActionDecisionReachesTheResponderAfterTheImmediateReply() = runTest {
        val h = LoopHarness(this)
        h.listening()
        val action = VoiceDecision.Act(VoiceActionDecision(VoiceActionKind("research_task")))
        h.ear.hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("look it up"), VoiceText("Sure."), action)))
        h.settle()
        assertEquals(action, h.responder.last.utterance.decision)
        assertEquals(listOf("Sure."), h.mouth.spokenTexts)
    }

    @Test
    fun keyDecisionsDriveTheLoop() = runTest {
        val h = LoopHarness(this)
        h.loop.onKey(KeyDecision.START, VoiceActivationKind.HOTKEY)
        h.settle()
        assertEquals(VoiceActivationKind.HOTKEY, h.state.activation)
        h.loop.onKey(KeyDecision.END_TURN, VoiceActivationKind.HOTKEY)
        h.settle()
        assertEquals(1, h.ear.commits)
        h.loop.onKey(KeyDecision.DISCARD, VoiceActivationKind.HOTKEY)
        h.settle()
        assertEquals(VoicePhase.IDLE, h.state.phase)
    }

    @Test
    fun earThatWillNotReleaseBlocksASecondEarUntilItDoes() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.ear.releaseDelay = 5.seconds
        h.loop.stop()
        advanceTimeBy(VoiceLoopConfig().releaseTimeout + 1.seconds)
        h.settle()
        assertEquals(VoiceError(VoiceErrorCode.UNRESPONSIVE, VoicePort.EAR), h.state.failure)
        h.listening()
        assertEquals(1, h.ear.opens())
        assertEquals(VoicePhase.FAILED, h.state.phase)
        advanceTimeBy(5.seconds)
        h.listening()
        assertEquals(2, h.ear.opens())
        assertEquals(VoicePhase.LISTENING, h.state.phase)
    }

    @Test
    fun lateReplyWithoutBargeInClosesTheEarBeforeSpeaking() = runTest {
        val h = LoopHarness(this, noBargeIn)
        h.speaking(VoiceText("On it. "))
        h.reply(VoiceReplyEvent.AwaitingLateReply)
        h.finishSpeech()
        assertTrue(h.ear.listening)
        h.replyText(VoiceText("Research done. "))
        val entries = h.log.entries
        assertTrue(entries.lastIndexOf("ear:close") < entries.lastIndexOf("mouth:speak"))
        assertFalse(h.ear.listening)
    }

    @Test
    fun mouthThatWillNotStopKeepsTheEarClosed() = runTest {
        val h = LoopHarness(this, noBargeIn)
        h.speaking()
        h.mouth.hangOnStop = true
        h.loop.activate(VoiceActivationKind.TAP)
        advanceTimeBy(VoiceLoopConfig().releaseTimeout + 1.seconds)
        h.settle()
        assertEquals(VoiceError(VoiceErrorCode.UNRESPONSIVE, VoicePort.MOUTH), h.state.failure)
        assertEquals(1, h.ear.opens())
    }

    @Test
    fun stopReportsAnAnswerThatWillNotStop() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.responder.last.cancelOutcome = VoiceCancelOutcome.UNRESPONSIVE
        h.loop.stop()
        h.settle()
        assertEquals(VoiceError(VoiceErrorCode.UNRESPONSIVE, VoicePort.RESPONDER), h.state.failure)
    }

    @Test
    fun firstTextWithoutASentenceEndStopsTheThinkingTimer() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()
        h.replyText(VoiceText("Hello"))
        advanceTimeBy(VoiceLoopConfig().thinkingTimeout + 1.seconds)
        h.settle()
        assertEquals(VoicePhase.THINKING, h.state.phase)
        h.reply(VoiceReplyEvent.Completed)
        assertEquals(listOf("Hello"), h.mouth.spokenTexts)
    }

    @Test
    fun shutdownReleasesEveryEngineBeforeReturning() = runTest {
        val h = LoopHarness(this)
        h.speaking()
        h.loop.shutdown()
        assertEquals(1, h.mouth.stops)
        assertEquals(1, h.responder.last.cancels)
        assertFalse(h.ear.listening)
        assertEquals(VoicePhase.IDLE, h.state.phase)
    }

    @Test
    fun retryIsHandedToTheEarOnceItListensAndFailsWhenTheCaptureExpired() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.ear.retryAccepted = false
        h.ear.hear(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, VoicePort.EAR), CapturedAudioHandle(3)))
        h.settle()
        h.loop.retryCapture()
        h.settle()
        val entries = h.log.entries
        assertTrue(entries.lastIndexOf("ear:open") < entries.indexOf("ear:retry"))
        assertEquals(VoiceError(VoiceErrorCode.CAPTURE_EXPIRED, VoicePort.EAR), h.state.failure)
    }

    private fun source(vararg events: VoiceActivationEvent): VoiceActivationSource = object : VoiceActivationSource {
        override val events: Flow<VoiceActivationEvent> = flowOf(*events)
    }
}
