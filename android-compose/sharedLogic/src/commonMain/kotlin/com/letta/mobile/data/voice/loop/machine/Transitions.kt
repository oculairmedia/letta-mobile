package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechSkipReason
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

/** This model with no speech outstanding and nothing shown as playing. */
internal fun LoopModel.silenced(): LoopModel = copy(
    speech = SpeechQueue(nextId = speech.nextId),
    view = view.copy(speaking = null, outputLevel = AudioLevel.Silent),
)

/** A [LoopEffect.SpeechSkipped] for [segments], or nothing when there were none. */
internal fun skipped(reason: SpeechSkipReason, segments: Int): List<LoopEffect> =
    if (segments > 0) listOf(LoopEffect.SpeechSkipped(reason, segments)) else emptyList()

/** Whether any answer may still arrive: pending, being interrupted, or delegated. */
private fun LoopModel.hasAnswers(): Boolean {
    val open = turn.replies.isNotEmpty() || turn.interrupting.isNotEmpty()
    return open || late.delegated.isNotEmpty()
}

/** Transitions shared by every rule set. */
internal object Transitions {
    /** Stops everything and settles in [phase] (IDLE or FAILED), reporting [failure]. */
    fun halt(model: LoopModel, phase: VoicePhase, failure: VoiceError?): Step {
        val effects = buildList {
            if (model.outputActive) add(LoopEffect.StopMouth)
            if (model.hasAnswers()) add(LoopEffect.CancelAllReplies)
        }
        val halted = model.copy(
            phase = phase,
            turn = TurnState(nextKey = model.turn.nextKey),
            speech = SpeechQueue(nextId = model.speech.nextId),
            late = LateState(),
            view = model.view.copy(
                speaking = null,
                outputLevel = AudioLevel.Silent,
                failure = failure,
                turnFailure = null,
                retryUtterance = null,
            ),
            pendingRetry = null,
        )
        return Step(halted, effects)
    }

    /** Stops everything and reports [error]: only for failures the loop cannot go on after. */
    fun fail(model: LoopModel, error: VoiceError): Step = halt(model, VoicePhase.FAILED, error)

    /** Queues [text] for the mouth under a new segment id, for the speech queue's current owner. */
    fun speak(model: LoopModel, text: VoiceText): Step {
        val id = SpeechSegmentId(model.speech.nextId)
        val speech = model.speech.copy(outstanding = model.speech.outstanding + (id to text), nextId = model.speech.nextId + 1)
        return Step(model.copy(speech = speech), listOf(LoopEffect.Speak(SpeechSegment(id, text))))
    }

    /**
     * Queues every text in [texts] for [owner] (null: standalone speech), moves to speaking and
     * flushes: these texts are complete, so a buffering mouth must not wait for more.
     */
    fun speakAll(model: LoopModel, texts: List<VoiceText>, owner: TurnKey? = null): Step {
        val speaking = model.copy(phase = VoicePhase.SPEAKING, speech = model.speech.copy(owner = owner))
        return texts.fold(Step(speaking)) { step, text ->
            val next = speak(step.model, text)
            Step(next.model, step.effects + next.effects)
        } + listOf(LoopEffect.FlushMouth)
    }

    /** Speaks the late text held while the person was speaking. */
    fun speakHeld(model: LoopModel): Step =
        speakAll(model.copy(late = model.late.copy(held = emptyList())), model.late.held)

    /** After the queue drained (or nothing was playing): the pending answers move on. */
    fun maybeFinish(model: LoopModel): Step = if (model.outputActive) Step(model) else resume(model)

    /**
     * The next pending answer takes its turn once nothing is playing: answers that ended with
     * nothing to say are dropped, an answer holding text is spoken, an open one is awaited in
     * THINKING, and with none left the turn is over. While the person is speaking nothing starts
     * (with nothing left to await, the loop shows that it is listening to them).
     */
    fun resume(model: LoopModel): Step {
        val head = model.turn.head
        return when {
            model.turn.userSpeaking -> Step(if (head == null) model.copy(phase = VoicePhase.LISTENING) else model)
            head == null -> afterTurn(model)
            !head.open && head.held.isEmpty() -> resume(model.withTurn { without(head.key) })
            head.held.isNotEmpty() -> promote(model, head)
            else -> Step(model.copy(phase = VoicePhase.THINKING))
        }
    }

    /** Speaks the text [head] held; a head that has ended leaves the queue (its speech plays on). */
    private fun promote(model: LoopModel, head: PendingReply): Step {
        val emptied = model.withTurn { if (head.open) update(head.key) { copy(held = emptyList()) } else without(head.key) }
        return speakAll(emptied, head.held, owner = head.key)
    }

    /** No answer is pending: speak held late text, or go back to listening (or idle). */
    fun afterTurn(model: LoopModel): Step {
        val cleared = model.withView { copy(speaking = null, outputLevel = AudioLevel.Silent) }
        return when {
            cleared.late.held.isNotEmpty() -> speakHeld(cleared)
            else -> Step(cleared.copy(phase = restingPhase(cleared)))
        }
    }

    private fun restingPhase(model: LoopModel): VoicePhase = when (model.config.mode) {
        VoiceConversationMode.CONTINUOUS -> VoicePhase.LISTENING
        VoiceConversationMode.SINGLE_SHOT -> VoicePhase.IDLE
    }
}

/** The person cutting in: by voice over the answer being spoken, or by a tap or a key. */
internal object Interrupts {
    /**
     * The person spoke over the answer being spoken: playback stops at once and the rest of that
     * answer is dropped (its run is asked to stop when it is still streaming). Answers queued
     * behind it stay pending and are spoken after the person's turn.
     */
    fun bargeIn(model: LoopModel): Step {
        val speaking = model.speech.owner?.let(model.turn::find)
        val effects = buildList {
            if (model.outputActive) add(LoopEffect.StopMouth)
            addAll(skipped(SpeechSkipReason.BARGE_IN, model.speech.outstanding.size))
            speaking?.let { add(LoopEffect.RequestInterrupt(it.key)) }
        }
        val turn = if (speaking == null) model.turn else stopping(model.turn, listOf(speaking))
        val listening = model.silenced().copy(phase = VoicePhase.LISTENING, turn = turn.copy(userSpeaking = true))
        return Step(listening, effects)
    }

    /**
     * The person interrupted (a tap or a key while answering): playback stops, every pending answer
     * is dropped and the open ones are asked to stop, and the loop listens.
     */
    fun interruptAll(model: LoopModel): Step {
        val replies = model.turn.replies
        val open = replies.filter { it.open }
        val dropped = model.speech.outstanding.size + replies.sumOf { it.held.size }
        val effects = buildList {
            if (model.outputActive) add(LoopEffect.StopMouth)
            addAll(skipped(SpeechSkipReason.INTERRUPTED, dropped))
            open.forEach { add(LoopEffect.RequestInterrupt(it.key)) }
        }
        val turn = stopping(model.turn, open).copy(replies = emptyList())
        return Step(model.silenced().copy(phase = VoicePhase.LISTENING, turn = turn), effects)
    }

    /** [turn] with [stopped] taken out of the pending answers and marked as interrupting. */
    private fun stopping(turn: TurnState, stopped: List<PendingReply>): TurnState {
        val keys = stopped.mapTo(mutableSetOf()) { it.key }
        return turn.copy(replies = turn.replies.filterNot { it.key in keys }, interrupting = turn.interrupting + keys)
    }
}
