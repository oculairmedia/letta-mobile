package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.loop.LoopStateProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** The words of the turn in progress reach the published state and leave it when the turn ends. */
class PartialTranscriptStateTest {
    private val idle = LoopModel(VoiceLoopConfig())

    private fun LoopModel.then(vararg inputs: LoopInput): LoopModel =
        inputs.fold(this) { model, input -> VoiceLoopMachine.reduce(model, input).model }

    private fun ear(event: VoiceEarEvent) = LoopInput.FromEar(event)

    private fun partial(text: String) = ear(VoiceEarEvent.PartialTranscript(VoiceText(text)))

    private val listening = idle.then(LoopInput.Activate(VoiceActivationKind.TAP), ear(VoiceEarEvent.ListeningStarted))

    private fun LoopModel.published() = LoopStateProjection.project(this, Instant.fromEpochMilliseconds(0)).partialTranscript

    @Test
    fun partialsStreamIntoTheState() {
        val hearing = listening.then(ear(VoiceEarEvent.SpeechStarted), partial("hel"))
        assertEquals(VoiceText("hel"), hearing.published())
        assertEquals(VoiceText("hello"), hearing.then(partial("hello")).published())
    }

    @Test
    fun theFinalTurnClearsThem() {
        val turn = ear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("hello there"))))
        val sent = listening.then(ear(VoiceEarEvent.SpeechStarted), partial("hello"), turn)
        assertNull(sent.published())
    }

    @Test
    fun aDroppedTurnClearsThem() {
        val dropped = listening.then(partial("noise"), ear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText.Empty))))
        assertNull(dropped.published())
    }

    @Test
    fun stoppingClearsThem() {
        assertNull(listening.then(partial("hello"), LoopInput.Stop()).published())
    }

    @Test
    fun anEarFailureClearsThem() {
        val failed = listening.then(partial("hello"), ear(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.ENGINE_FAILED, VoicePort.EAR))))
        assertNull(failed.published())
    }
}
