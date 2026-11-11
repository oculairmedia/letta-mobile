package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceConversationMode
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId

/** This model with its view changed by [change]. */
internal fun LoopModel.withView(change: LoopView.() -> LoopView): LoopModel = copy(view = view.change())

/** This model with its turn changed by [change]. */
internal fun LoopModel.withTurn(change: TurnState.() -> TurnState): LoopModel = copy(turn = turn.change())

/** Transitions shared by every rule set. */
internal object Transitions {
    /** Stops everything and settles in [phase] (IDLE or FAILED), reporting [failure]. */
    fun halt(model: LoopModel, phase: VoicePhase, failure: VoiceError?): Step {
        val effects = buildList {
            if (model.outputActive) add(LoopEffect.StopMouth)
            if (hasAnswers(model)) add(LoopEffect.CancelAllReplies)
        }
        val halted = model.copy(
            phase = phase,
            turn = TurnState(nextKey = model.turn.nextKey),
            speech = SpeechQueue(nextId = model.speech.nextId),
            late = LateState(),
            view = model.view.copy(speaking = null, outputLevel = AudioLevel.Silent, failure = failure),
        )
        return Step(halted, effects)
    }

    private fun hasAnswers(model: LoopModel): Boolean {
        val turn = model.turn
        val open = turn.active != null || turn.interrupting.isNotEmpty()
        return open || model.late.delegated.isNotEmpty()
    }

    /** Stops everything and reports [error]. */
    fun fail(model: LoopModel, error: VoiceError): Step = halt(model, VoicePhase.FAILED, error)

    /** Queues [text] for the mouth under a new segment id. */
    fun speak(model: LoopModel, text: VoiceText): Step {
        val id = SpeechSegmentId(model.speech.nextId)
        val speech = SpeechQueue(model.speech.outstanding + (id to text), model.speech.nextId + 1)
        return Step(model.copy(speech = speech), listOf(LoopEffect.Speak(SpeechSegment(id, text))))
    }

    /** Queues every text in [texts] and moves to speaking. */
    fun speakAll(model: LoopModel, texts: List<VoiceText>): Step =
        texts.fold(Step(model.copy(phase = VoicePhase.SPEAKING))) { step, text ->
            val next = speak(step.model, text)
            Step(next.model, step.effects + next.effects)
        }

    private fun speakHeld(model: LoopModel): Step =
        speakAll(model.copy(late = model.late.copy(held = emptyList())), model.late.held)

    /**
     * Interrupts the answer being awaited or spoken: stops the mouth, requests the responder stop
     * (its outcome arrives later) and listens. The interrupted answer's text is never spoken.
     */
    fun interrupt(model: LoopModel): Step {
        val active = model.turn.active?.takeIf { model.turn.replyOpen }
        val effects = buildList {
            if (model.outputActive) add(LoopEffect.StopMouth)
            active?.let { add(LoopEffect.RequestInterrupt(it)) }
        }
        val turn = model.turn.copy(
            active = null,
            replyOpen = false,
            pendingFailure = null,
            interrupting = model.turn.interrupting + listOfNotNull(active),
        )
        val listening = model.copy(
            phase = VoicePhase.LISTENING,
            turn = turn,
            speech = SpeechQueue(nextId = model.speech.nextId),
            view = model.view.copy(speaking = null, outputLevel = AudioLevel.Silent),
        )
        return Step(listening, effects)
    }

    /** After the queue drained: finish the turn when the answer is done too. */
    fun maybeFinish(model: LoopModel): Step =
        if (model.outputActive || model.turn.replyOpen) Step(model) else afterTurn(model)

    /** The turn is over: report a held failure, speak held late text, or go back to listening (or idle). */
    fun afterTurn(model: LoopModel): Step {
        val pending = model.turn.pendingFailure
        val cleared = model.withView { copy(speaking = null, outputLevel = AudioLevel.Silent) }
        return when {
            pending != null -> fail(cleared, pending)
            cleared.late.held.isNotEmpty() -> speakHeld(cleared)
            else -> Step(cleared.copy(phase = restingPhase(cleared)))
        }
    }

    private fun restingPhase(model: LoopModel): VoicePhase = when (model.config.mode) {
        VoiceConversationMode.CONTINUOUS -> VoicePhase.LISTENING
        VoiceConversationMode.SINGLE_SHOT -> VoicePhase.IDLE
    }
}
