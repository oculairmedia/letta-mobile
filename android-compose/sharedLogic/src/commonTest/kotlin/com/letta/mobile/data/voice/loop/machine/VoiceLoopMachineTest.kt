package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
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
}
