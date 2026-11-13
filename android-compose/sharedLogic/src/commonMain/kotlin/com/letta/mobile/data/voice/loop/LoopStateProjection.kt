package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.loop.machine.LoopModel
import com.letta.mobile.data.voice.loop.machine.TurnState
import kotlin.time.Instant

/** Projects the machine's model into the published [VoiceLoopState]. */
internal object LoopStateProjection {
    fun project(model: LoopModel, since: Instant): VoiceLoopState {
        val view = model.view
        return VoiceLoopState(
            phase = model.phase,
            transcript = view.transcript,
            speaking = view.speaking,
            inputLevel = view.inputLevel,
            outputLevel = view.outputLevel,
            microphoneOpen = view.micOpen,
            userSpeaking = model.turn.userSpeaking,
            failure = view.failure,
            activationNotice = view.notice,
            activation = view.activation,
            retryableCapture = view.retryable,
            interrupt = interruptStatus(model.turn),
            awaitingLateReply = model.late.delegated.isNotEmpty(),
            heldLateSegments = model.late.held.size,
            phaseSince = since,
        )
    }

    private fun interruptStatus(turn: TurnState): InterruptStatus = when {
        turn.interrupting.isEmpty() -> InterruptStatus.NONE
        turn.interruptUnresponsive -> InterruptStatus.UNRESPONSIVE
        else -> InterruptStatus.REQUESTED
    }
}
