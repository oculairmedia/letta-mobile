package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
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
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rows of the state table that the driver tests do not reach directly. */
class VoiceLoopMachineTest {
    private val idle = LoopModel(VoiceLoopConfig())

    private fun LoopModel.then(vararg inputs: LoopInput): Step =
        inputs.fold(Step(this)) { step, input -> VoiceLoopMachine.reduce(step.model, input).after(step.effects) }

    private val activate = LoopInput.Activate(VoiceActivationKind.TAP)

    private val earError = VoiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, VoicePort.EAR)

    private fun endOfTurn(words: VoiceText) = LoopInput.FromEar(VoiceEarEvent.EndOfTurn(VoiceUtterance(words)))

    @Test
    fun earEventsWhileIdleChangeNothing() {
        val step = idle.then(endOfTurn(VoiceText("hello")), LoopInput.FromEar(VoiceEarEvent.SpeechStarted))
        assertEquals(VoicePhase.IDLE, step.model.phase)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun emptyTurnKeepsListening() {
        val step = idle.then(activate, endOfTurn(VoiceText("  ")))
        assertEquals(VoicePhase.LISTENING, step.model.phase)
        assertTrue(step.effects.none { it is LoopEffect.Respond })
    }

    @Test
    fun tapWhileThinkingInterruptsEvenWithoutBargeIn() {
        val step = idle.copy(config = VoiceLoopConfig(bargeIn = false)).then(activate, endOfTurn(VoiceText("hi")), activate)
        assertEquals(VoicePhase.LISTENING, step.model.phase)
        assertTrue(step.effects.any { it is LoopEffect.RequestInterrupt })
    }

    @Test
    fun replyWithAnUnknownGenerationIsDropped() {
        val thinking = idle.then(activate, endOfTurn(VoiceText("hi"))).model
        val step = thinking.then(LoopInput.FromReply(TurnKey(99), ReplySignal.Segment(VoiceText("stale"))))
        assertEquals(VoicePhase.THINKING, step.model.phase)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun finishOfAnUnknownSegmentIsIgnored() {
        val thinking = idle.then(activate, endOfTurn(VoiceText("hi"))).model
        val step = thinking.then(LoopInput.FromMouth(VoiceMouthEvent.Finished(SpeechSegmentId(42))))
        assertEquals(thinking, step.model)
    }

    @Test
    fun endTurnOutsideListeningDoesNothing() {
        assertTrue(idle.then(LoopInput.EndTurn).effects.isEmpty())
        assertEquals(listOf<LoopEffect>(LoopEffect.CommitTurn), idle.then(activate, LoopInput.EndTurn).effects)
    }

    @Test
    fun microphoneIsWantedOnlyWhileListeningOrForBargeIn() {
        val listening = idle.then(activate).model
        assertTrue(listening.micWanted)
        val thinkingNoBargeIn = listening.copy(config = VoiceLoopConfig(bargeIn = false)).then(endOfTurn(VoiceText("hi"))).model
        assertEquals(false, thinkingNoBargeIn.micWanted)
        assertEquals(false, thinkingNoBargeIn.view.micOpen)
    }

    private val heard = LoopInput.FromEar(VoiceEarEvent.SpeechStarted)
    private val firstTurn = TurnKey(1)
    private val firstSegment = SpeechSegmentId(1)

    private fun segment(words: VoiceText) = LoopInput.FromReply(firstTurn, ReplySignal.Segment(words))

    /** One answer spoken, delegated, and its speech finished: back to listening with a late reply pending. */
    private fun delegated(): LoopModel = idle.then(
        activate,
        endOfTurn(VoiceText("look it up")),
        segment(VoiceText("On it.")),
        LoopInput.FromReply(firstTurn, ReplySignal.Delegated),
        LoopInput.FromMouth(VoiceMouthEvent.Finished(firstSegment)),
    ).model

    @Test
    fun standaloneSpeechIsFlushed() {
        val answered = VoiceUtterance(VoiceText("hi"), VoiceText("Hello!"), VoiceDecision.AnsweredByEar)
        val cases = listOf(
            idle.then(activate, LoopInput.FromEar(VoiceEarEvent.EndOfTurn(answered))).effects,
            delegated().then(segment(VoiceText("Done."))).effects,
        )
        cases.forEach { effects ->
            val speak = effects.indexOfFirst { it is LoopEffect.Speak }
            assertTrue(speak >= 0)
            assertEquals(LoopEffect.FlushMouth, effects[speak + 1])
        }
    }

    @Test
    fun heldLateTextIsSpokenAfterAnEmptyTurn() {
        val holding = delegated().then(heard, segment(VoiceText("Done."))).model
        assertEquals(1, holding.late.held.size)
        val step = holding.then(endOfTurn(VoiceText(" ")))
        assertEquals(VoicePhase.SPEAKING, step.model.phase)
        assertTrue(step.effects.any { it is LoopEffect.Speak })
    }

    @Test
    fun actionOnlyTurnReachesTheResponder() {
        val action = VoiceDecision.Act(VoiceActionDecision(VoiceActionKind("research_task")))
        val step = idle.then(activate, LoopInput.FromEar(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText.Empty, decision = action))))
        assertEquals(VoicePhase.THINKING, step.model.phase)
        assertTrue(step.effects.any { it is LoopEffect.Respond })
    }

    /**
     * A failed segment of speech used to fail the whole loop (FAILED). It is now reported as a turn
     * failure: the mouth is reset, the rest of that answer is dropped (no longer followed) and the
     * loop listens on. A failure for a segment no longer outstanding is still ignored.
     */
    @Test
    fun mouthFailureCountsOnlyForOutstandingSegments() {
        val speaking = idle.then(activate, endOfTurn(VoiceText("hi")), segment(VoiceText("It is noon."))).model
        val error = VoiceError(VoiceErrorCode.PLAYBACK_FAILED, VoicePort.MOUTH)
        val cases = mapOf(SpeechSegmentId(99) to VoicePhase.SPEAKING, firstSegment to VoicePhase.LISTENING)
        cases.forEach { (id, phase) ->
            assertEquals(phase, speaking.then(LoopInput.FromMouth(VoiceMouthEvent.Failed(error, id))).model.phase, "segment ${id.value}")
        }
        val failed = speaking.then(LoopInput.FromMouth(VoiceMouthEvent.Failed(error, firstSegment)))
        assertEquals(error, failed.model.view.turnFailure)
        assertEquals(null, failed.model.view.failure)
        assertTrue(LoopEffect.StopMouth(SpeechStopReason.MOUTH_FAILED) in failed.effects)
        assertTrue(LoopEffect.ReleaseReply(firstTurn) in failed.effects)
        assertTrue(LoopEffect.SpeechSkipped(SpeechSkipReason.MOUTH_FAILED, 1) in failed.effects)
    }

    @Test
    fun retryWaitsForTheEarAndActivationDropsAnOldHandle() {
        val failed = idle.then(activate, LoopInput.FromEar(VoiceEarEvent.Failed(earError, CapturedAudioHandle(4)))).model
        val waiting = failed.then(LoopInput.RetryCapture)
        assertTrue(waiting.effects.none { it is LoopEffect.RetryCapture })
        val listening = waiting.model.then(LoopInput.FromEar(VoiceEarEvent.ListeningStarted))
        assertEquals(listOf<LoopEffect>(LoopEffect.RetryCapture(CapturedAudioHandle(4))), listening.effects)
        assertEquals(null, failed.then(activate).model.view.retryable)
    }

    /** The timeout used to fail the loop (FAILED); it now fails only that answer and the loop listens on. */
    @Test
    fun firstTextEndsTheThinkingTimeout() {
        val thinking = idle.then(activate, endOfTurn(VoiceText("hi"))).model
        val cases = listOf(
            listOf<LoopInput>() to VoicePhase.LISTENING,
            listOf<LoopInput>(LoopInput.FromReply(firstTurn, ReplySignal.TextStarted)) to VoicePhase.THINKING,
        )
        cases.forEach { (before, phase) ->
            val inputs = before + LoopInput.FromReply(firstTurn, ReplySignal.ThinkingTimedOut)
            assertEquals(phase, thinking.then(*inputs.toTypedArray()).model.phase)
        }
    }

    private val secondTurn = TurnKey(2)

    private fun said(effects: List<LoopEffect>): List<String> = effects.filterIsInstance<LoopEffect.Speak>().map { it.segment.text.value }

    private fun finished(id: Long) = LoopInput.FromMouth(VoiceMouthEvent.Finished(SpeechSegmentId(id)))

    @Test
    fun twoUtterancesWhileThinkingAreBothAnsweredInTheOrderSent() {
        val step = idle.then(
            activate,
            endOfTurn(VoiceText("first")),
            heard,
            endOfTurn(VoiceText("second")),
            LoopInput.FromReply(secondTurn, ReplySignal.Segment(VoiceText("Two."))),
            segment(VoiceText("One.")),
            LoopInput.FromReply(firstTurn, ReplySignal.Completed),
            LoopInput.FromReply(secondTurn, ReplySignal.Completed),
        )
        assertTrue(step.effects.none { it is LoopEffect.RequestInterrupt || it is LoopEffect.CancelAllReplies })
        assertEquals(listOf(firstTurn, secondTurn), step.effects.filterIsInstance<LoopEffect.Respond>().map { it.turn })
        assertEquals(listOf("One."), said(step.effects))
        val afterFirst = step.model.then(finished(1))
        assertEquals(listOf("Two."), said(afterFirst.effects))
        assertEquals(VoicePhase.SPEAKING, afterFirst.model.phase)
        assertEquals(VoicePhase.LISTENING, afterFirst.model.then(finished(2)).model.phase)
    }

    @Test
    fun answerTextArrivingWhileThePersonSpeaksWaitsUntilTheyFinish() {
        val thinking = idle.then(activate, endOfTurn(VoiceText("first")), heard).model
        val holding = thinking.then(segment(VoiceText("One.")))
        assertEquals(emptyList(), said(holding.effects))
        assertEquals(VoicePhase.THINKING, holding.model.phase)
        val spoken = holding.model.then(endOfTurn(VoiceText("and also")))
        assertEquals(listOf("One."), said(spoken.effects))
        assertEquals(VoicePhase.SPEAKING, spoken.model.phase)
        assertEquals(2, spoken.model.turn.replies.size)
    }

    @Test
    fun bargeInStopsPlaybackDropsTheRestOfThatAnswerAndSendsTheNewUtterance() {
        val speaking = idle.then(activate, endOfTurn(VoiceText("first")), segment(VoiceText("One."))).model
        val barged = speaking.then(heard)
        assertEquals(VoicePhase.LISTENING, barged.model.phase)
        assertEquals(
            listOf(LoopEffect.StopMouth(SpeechStopReason.BARGE_IN), LoopEffect.SpeechSkipped(SpeechSkipReason.BARGE_IN, 1), LoopEffect.RequestInterrupt(firstTurn)),
            barged.effects,
        )
        val next = barged.model.then(
            endOfTurn(VoiceText("second")),
            segment(VoiceText("Rest of one.")),
            LoopInput.FromReply(secondTurn, ReplySignal.Segment(VoiceText("Two."))),
        )
        val respond = LoopEffect.Respond(secondTurn, VoiceUtterance(VoiceText("second")))
        assertEquals(listOf(respond), next.effects.filterIsInstance<LoopEffect.Respond>())
        assertEquals(listOf("Two."), said(next.effects))
    }

    @Test
    fun undeliveredUtteranceIsReportedAndTheLoopListensOn() {
        val thinking = idle.then(activate, endOfTurn(VoiceText("hi"))).model
        val error = VoiceError(VoiceErrorCode.NOT_DELIVERED, VoicePort.RESPONDER)
        val step = thinking.then(LoopInput.FromReply(firstTurn, ReplySignal.Failed(error)))
        assertEquals(VoicePhase.LISTENING, step.model.phase)
        assertTrue(step.model.micWanted)
        assertEquals(null, step.model.view.failure)
        assertEquals(error, step.model.view.turnFailure)
        assertTrue(step.effects.none { it is LoopEffect.CancelAllReplies || it is LoopEffect.StopMouth })
        val retried = step.model.then(LoopInput.RetryReply)
        assertEquals(listOf(LoopEffect.Respond(secondTurn, VoiceUtterance(VoiceText("hi")))), retried.effects)
        assertEquals(VoicePhase.THINKING, retried.model.phase)
        assertEquals(null, retried.model.view.turnFailure)
    }

    @Test
    fun aFailedFollowUpLeavesTheAnswerBeforeItAlone() {
        val error = VoiceError(VoiceErrorCode.RUN_FAILED, VoicePort.RESPONDER)
        val step = idle.then(
            activate,
            endOfTurn(VoiceText("first")),
            endOfTurn(VoiceText("second")),
            LoopInput.FromReply(secondTurn, ReplySignal.Failed(error)),
            segment(VoiceText("One.")),
        )
        assertEquals(listOf("One."), said(step.effects))
        assertEquals(error, step.model.view.turnFailure)
        assertEquals(listOf(firstTurn), step.model.turn.replies.map { it.key })
    }

    @Test
    fun pastThePendingLimitTheOldestUnspokenAnswerIsDropped() {
        val utterances = (1..5).map { endOfTurn(VoiceText("utterance $it")) }
        val step = idle.copy(config = VoiceLoopConfig(maxPendingReplies = 4)).then(activate, *utterances.toTypedArray())
        assertEquals((2L..5L).map(::TurnKey), step.model.turn.replies.map { it.key })
        assertTrue(LoopEffect.ReleaseReply(firstTurn) in step.effects)
        assertTrue(LoopEffect.SpeechSkipped(SpeechSkipReason.PENDING_LIMIT, 1) in step.effects)
    }

    private val halfDuplex = idle.copy(config = VoiceLoopConfig(voiceInterrupts = false))

    private val closed = LoopInput.FromEar(VoiceEarEvent.SpeechEnded)

    @Test
    fun halfDuplexSpeechReportedOverTheOutputInterruptsNothing() {
        val speaking = halfDuplex.then(activate, endOfTurn(VoiceText("first")), segment(VoiceText("One."))).model
        val step = speaking.then(heard)
        assertEquals(VoicePhase.SPEAKING, step.model.phase)
        assertTrue(step.effects.isEmpty())
        assertEquals(false, step.model.turn.userSpeaking)
        assertTrue(step.model.micWanted, "half-duplex keeps the microphone open; the ear ignores it during output")
    }

    @Test
    fun aPressWhileSpeakingStopsPlaybackAndListensInEitherMode() {
        listOf(idle, halfDuplex).forEach { start ->
            val speaking = start.then(activate, endOfTurn(VoiceText("first")), segment(VoiceText("One."))).model
            val pressed = speaking.then(activate)
            assertEquals(VoicePhase.LISTENING, pressed.model.phase)
            assertEquals(LoopEffect.StopMouth(SpeechStopReason.BUTTON), pressed.effects.first())
            assertEquals(false, pressed.model.outputActive)
            assertTrue(pressed.model.micWanted)
        }
    }

    @Test
    fun aFinishedAnswerHeldByAShortSoundIsSpokenWhenTheTurnClosesNotWhenItsTranscriptArrives() {
        listOf(idle, halfDuplex).forEach { start ->
            val holding = start.then(
                activate,
                endOfTurn(VoiceText("first")),
                heard,
                segment(VoiceText("One.")),
                LoopInput.FromReply(firstTurn, ReplySignal.Completed),
            )
            assertEquals(emptyList(), said(holding.effects))
            val released = holding.model.then(closed)
            assertEquals(listOf("One."), said(released.effects))
            assertEquals(VoicePhase.SPEAKING, released.model.phase)
            assertEquals(1, released.model.turn.transcribing)
            // The short sound transcribes to nothing: the answer plays on, untouched.
            val nothing = released.model.then(endOfTurn(VoiceText("  ")))
            assertTrue(nothing.effects.isEmpty())
            assertEquals(VoicePhase.SPEAKING, nothing.model.phase)
            assertEquals(0, nothing.model.turn.transcribing)
        }
    }

    @Test
    fun aTranscriptArrivingWhileTheReleasedAnswerPlaysIsAFollowUpNotABargeIn() {
        val released = idle.then(activate, endOfTurn(VoiceText("first")), heard, segment(VoiceText("One.")), closed).model
        val followUp = released.then(endOfTurn(VoiceText("and also")))
        assertTrue(followUp.effects.none { it is LoopEffect.StopMouth || it is LoopEffect.RequestInterrupt })
        assertEquals(listOf(LoopEffect.Respond(secondTurn, VoiceUtterance(VoiceText("and also")))), followUp.effects)
        assertEquals(VoicePhase.SPEAKING, followUp.model.phase)
        assertEquals(listOf(firstTurn, secondTurn), followUp.model.turn.replies.map { it.key })
    }

    @Test
    fun aSingleShotKeepsListeningWhileTheClosedTurnIsTranscribed() {
        val single = idle.copy(config = VoiceLoopConfig(mode = VoiceConversationMode.SINGLE_SHOT))
        val step = single.then(activate, heard, closed)
        assertEquals(VoicePhase.LISTENING, step.model.phase)
        assertTrue(step.model.micWanted)
    }
}
