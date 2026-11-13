package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.activation.VoiceActivationKind

/**
 * The voice loop as a pure function from (model, input) to (model, effects). The driver runs the
 * effects; nothing here suspends, reads a clock or touches an engine.
 */
internal object VoiceLoopMachine {
    /** Applies [input] to [model]. */
    fun reduce(model: LoopModel, input: LoopInput): Step {
        val step = when (input) {
            is LoopInput.FromEar -> EarRules.on(model, input.event)
            is LoopInput.FromMouth -> MouthRules.on(model, input.event)
            is LoopInput.FromReply -> ReplyRules.on(model, input.turn, input.signal)
            is LoopInput.InterruptSettled -> ReplyRules.settled(model, input.turn, input.outcome)
            else -> ControlRules.on(model, input)
        }
        return closeMicView(step)
    }

    private fun closeMicView(step: Step): Step {
        if (step.model.micWanted) return step
        return step.copy(model = step.model.withView { copy(micOpen = false, inputLevel = AudioLevel.Silent) })
    }
}

/** The person's controls and engine faults. */
internal object ControlRules {
    fun on(model: LoopModel, input: LoopInput): Step = when (input) {
        is LoopInput.Activate -> activate(model, input.kind)
        LoopInput.EndTurn -> endTurn(model)
        is LoopInput.Stop -> Transitions.halt(model, VoicePhase.IDLE, null)
        LoopInput.RetryCapture, LoopInput.RetryReply -> retries(model, input)
        is LoopInput.Notice -> Step(model.withView { copy(notice = input.error) })
        is LoopInput.Fault -> Transitions.fail(model, input.error)
        else -> Step(model)
    }

    /** Ending the turn commits what was heard, and only while listening. */
    private fun endTurn(model: LoopModel): Step =
        if (model.phase == VoicePhase.LISTENING) Step(model, listOf(LoopEffect.CommitTurn)) else Step(model)

    private fun activate(model: LoopModel, kind: VoiceActivationKind): Step = when (model.phase) {
        VoicePhase.IDLE, VoicePhase.FAILED -> Step(listen(model, kind))
        VoicePhase.THINKING, VoicePhase.SPEAKING -> Interrupts.interruptAll(model)
        VoicePhase.LISTENING -> Step(model)
    }

    private fun listen(model: LoopModel, kind: VoiceActivationKind): LoopModel = model.copy(
        phase = VoicePhase.LISTENING,
        view = model.view.copy(
            failure = null,
            transcript = null,
            activation = kind,
            retryable = null,
            turnFailure = null,
            retryUtterance = null,
        ),
    )

    private fun retries(model: LoopModel, input: LoopInput): Step =
        if (input == LoopInput.RetryCapture) retry(model) else retryReply(model)

    /**
     * Listens again and hands the retained capture to the ear once it reports listening; an ear
     * that no longer holds the capture turns into a CAPTURE_EXPIRED fault.
     */
    private fun retry(model: LoopModel): Step {
        val handle = model.view.retryable ?: return Step(model)
        val resting = model.phase == VoicePhase.IDLE || model.phase == VoicePhase.FAILED
        if (!resting && model.phase != VoicePhase.LISTENING) return Step(model)
        val listening = model.copy(phase = VoicePhase.LISTENING, view = model.view.copy(failure = null, retryable = null))
        if (listening.view.micOpen) return Step(listening, listOf(LoopEffect.RetryCapture(handle)))
        return Step(listening.copy(pendingRetry = handle))
    }

    /** Sends again the utterance whose answer failed, as a new turn, while the loop is running. */
    private fun retryReply(model: LoopModel): Step {
        val utterance = model.view.retryUtterance ?: return Step(model)
        if (model.phase == VoicePhase.IDLE || model.phase == VoicePhase.FAILED) return Step(model)
        return Turns.start(model, utterance)
    }
}
