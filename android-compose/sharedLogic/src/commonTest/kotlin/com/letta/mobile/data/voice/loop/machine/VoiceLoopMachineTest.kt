package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceActionDecision
import com.letta.mobile.data.voice.VoiceActionKind
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

    @Test
    fun mouthFailureCountsOnlyForOutstandingSegments() {
        val speaking = idle.then(activate, endOfTurn(VoiceText("hi")), segment(VoiceText("It is noon."))).model
        val error = VoiceError(VoiceErrorCode.PLAYBACK_FAILED, VoicePort.MOUTH)
        val cases = mapOf(SpeechSegmentId(99) to VoicePhase.SPEAKING, firstSegment to VoicePhase.FAILED)
        cases.forEach { (id, phase) ->
            assertEquals(phase, speaking.then(LoopInput.FromMouth(VoiceMouthEvent.Failed(error, id))).model.phase, "segment ${id.value}")
        }
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

    @Test
    fun firstTextEndsTheThinkingTimeout() {
        val thinking = idle.then(activate, endOfTurn(VoiceText("hi"))).model
        val cases = listOf(
            listOf<LoopInput>() to VoicePhase.FAILED,
            listOf<LoopInput>(LoopInput.FromReply(firstTurn, ReplySignal.TextStarted)) to VoicePhase.THINKING,
        )
        cases.forEach { (before, phase) ->
            val inputs = before + LoopInput.FromReply(firstTurn, ReplySignal.ThinkingTimedOut)
            assertEquals(phase, thinking.then(*inputs.toTypedArray()).model.phase)
        }
    }
}
