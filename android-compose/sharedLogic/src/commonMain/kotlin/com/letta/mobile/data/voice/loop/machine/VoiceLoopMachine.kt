package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceDecision
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent

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
        LoopInput.EndTurn -> if (model.phase == VoicePhase.LISTENING) Step(model, listOf(LoopEffect.CommitTurn)) else Step(model)
        is LoopInput.Stop -> Transitions.halt(model, VoicePhase.IDLE, null)
        LoopInput.RetryCapture -> retry(model)
        is LoopInput.Notice -> Step(model.withView { copy(notice = input.error) })
        is LoopInput.Fault -> Transitions.fail(model, input.error)
        else -> Step(model)
    }

    private fun activate(model: LoopModel, kind: VoiceActivationKind): Step = when (model.phase) {
        VoicePhase.IDLE, VoicePhase.FAILED -> Step(listen(model, kind))
        VoicePhase.THINKING, VoicePhase.SPEAKING -> Transitions.interrupt(model)
        VoicePhase.LISTENING -> Step(model)
    }

    private fun listen(model: LoopModel, kind: VoiceActivationKind): LoopModel = model.copy(
        phase = VoicePhase.LISTENING,
        view = model.view.copy(failure = null, transcript = null, activation = kind, retryable = null),
    )

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
}

/** What the ear reports. */
internal object EarRules {
    fun on(model: LoopModel, event: VoiceEarEvent): Step = when (event) {
        VoiceEarEvent.ListeningStarted -> listening(model)
        is VoiceEarEvent.InputLevel -> Step(model.withView { copy(inputLevel = event.level) })
        is VoiceEarEvent.PartialTranscript -> Step(model.withView { copy(transcript = event.text) })
        VoiceEarEvent.SpeechStarted -> speechStarted(model)
        is VoiceEarEvent.EndOfTurn -> endOfTurn(model, event.utterance)
        is VoiceEarEvent.Failed -> Transitions.fail(model, event.error).let { step ->
            step.copy(model = step.model.withView { copy(retryable = event.retry) })
        }
    }

    private fun listening(model: LoopModel): Step {
        val open = model.withView { copy(micOpen = true) }
        val handle = model.pendingRetry ?: return Step(open)
        return Step(open.copy(pendingRetry = null), listOf(LoopEffect.RetryCapture(handle)))
    }

    private fun speechStarted(model: LoopModel): Step = when (model.phase) {
        VoicePhase.THINKING, VoicePhase.SPEAKING -> bargeIn(model)
        VoicePhase.LISTENING -> Step(model.withTurn { copy(userSpeaking = true) }.withView { copy(retryable = null) })
        else -> Step(model)
    }

    private fun bargeIn(model: LoopModel): Step {
        val step = Transitions.interrupt(model)
        return step.copy(model = step.model.withTurn { copy(userSpeaking = true) })
    }

    private fun endOfTurn(model: LoopModel, utterance: VoiceUtterance): Step = when (model.phase) {
        VoicePhase.LISTENING -> startTurn(model, utterance)
        VoicePhase.THINKING, VoicePhase.SPEAKING -> bargeIn(model).let { step -> startTurn(step.model, utterance).after(step.effects) }
        else -> Step(model)
    }

    private fun startTurn(model: LoopModel, utterance: VoiceUtterance): Step {
        val heard = model.withTurn { copy(userSpeaking = false) }.withView { copy(transcript = utterance.text) }
        if (utterance.isEmpty) return emptyTurn(heard)
        val key = TurnKey(heard.turn.nextKey)
        val keyed = heard.withTurn { copy(nextKey = nextKey + 1) }
        val spoken = utterance.immediateReply?.let { Transitions.speak(keyed, it) + listOf(LoopEffect.FlushMouth) } ?: Step(keyed)
        val answered = answer(spoken, key, utterance)
        val next = answered.model
        return when {
            next.outputActive -> Step(next.copy(phase = VoicePhase.SPEAKING), answered.effects)
            next.turn.replyOpen -> Step(next.copy(phase = VoicePhase.THINKING), answered.effects)
            else -> Transitions.afterTurn(next).after(answered.effects)
        }
    }

    /** Nothing was said; late text held while the person spoke is spoken now. */
    private fun emptyTurn(model: LoopModel): Step =
        if (model.late.held.isEmpty()) Step(model) else Transitions.speakHeld(model)

    private fun answer(step: Step, key: TurnKey, utterance: VoiceUtterance): Step {
        if (utterance.decision == VoiceDecision.AnsweredByEar) return step
        val awaiting = step.model.withTurn { copy(active = key, replyOpen = true, textArrived = false) }
        return Step(awaiting, step.effects + LoopEffect.Respond(key, utterance))
    }
}

/** What the mouth reports. */
internal object MouthRules {
    fun on(model: LoopModel, event: VoiceMouthEvent): Step = when (event) {
        is VoiceMouthEvent.Started -> Step(model.withView { copy(speaking = model.speech.outstanding[event.id] ?: speaking) })
        is VoiceMouthEvent.Finished -> finished(model, event)
        is VoiceMouthEvent.OutputLevel -> level(model, event)
        is VoiceMouthEvent.Failed -> if (event.id in model.speech.outstanding) Transitions.fail(model, event.error) else Step(model)
    }

    private fun level(model: LoopModel, event: VoiceMouthEvent.OutputLevel): Step =
        if (model.outputActive) Step(model.withView { copy(outputLevel = event.level) }) else Step(model)

    private fun finished(model: LoopModel, event: VoiceMouthEvent.Finished): Step {
        if (event.id !in model.speech.outstanding) return Step(model)
        val drained = model.copy(
            speech = model.speech.copy(outstanding = model.speech.outstanding - event.id),
            view = model.view.copy(speaking = null, outputLevel = AudioLevel.Silent),
        )
        return if (drained.phase == VoicePhase.SPEAKING) Transitions.maybeFinish(drained) else Step(drained)
    }
}
