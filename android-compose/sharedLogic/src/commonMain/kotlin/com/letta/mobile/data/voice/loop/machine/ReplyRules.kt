package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.LateReplyPolicy
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome

/**
 * What answers report, routed by generation token: the active turn's text is spoken, an
 * interrupted turn's text is dropped, a delegated turn's text is a late reply, and any other
 * token is stale and dropped.
 */
internal object ReplyRules {
    fun on(model: LoopModel, turn: TurnKey, signal: ReplySignal): Step = when (turn) {
        model.turn.active -> current(model, signal)
        in model.turn.interrupting -> interrupted(model, turn, signal)
        in model.late.delegated -> LateReplyRules.on(model, turn, signal)
        else -> Step(model)
    }

    /** A requested interrupt of [turn] settled. */
    fun settled(model: LoopModel, turn: TurnKey, outcome: VoiceCancelOutcome): Step {
        if (turn !in model.turn.interrupting) return Step(model)
        if (outcome == VoiceCancelOutcome.UNRESPONSIVE) return Step(model.withTurn { copy(interruptUnresponsive = true) })
        return Step(release(model, turn), listOf(LoopEffect.ReleaseReply(turn)))
    }

    private fun current(model: LoopModel, signal: ReplySignal): Step = when (signal) {
        is ReplySignal.Segment -> Transitions.speak(model.copy(phase = VoicePhase.SPEAKING), signal.text)
        ReplySignal.Delegated -> close(model, delegate = true)
        ReplySignal.Completed -> close(model, delegate = false)
        is ReplySignal.Failed -> failed(model, signal.error)
        ReplySignal.ThinkingTimedOut -> timedOut(model)
        ReplySignal.TextStarted -> Step(model.withTurn { copy(textArrived = true) })
    }

    /**
     * The first-text timeout fails an open answer that has produced no text at all, in whatever
     * phase (an immediate reply may have been spoken meanwhile). It fails at once: any immediate
     * speech still playing is stopped and the answer is cancelled, rather than left to finish.
     */
    private fun timedOut(model: LoopModel): Step = when {
        !model.turn.replyOpen -> Step(model)
        model.turn.textArrived -> Step(model)
        else -> Transitions.fail(model, VoiceError(VoiceErrorCode.TIMED_OUT, VoicePort.RESPONDER))
    }

    private fun close(model: LoopModel, delegate: Boolean): Step {
        val turn = model.turn.active
        val late = if (delegate) model.late.copy(delegated = model.late.delegated + listOfNotNull(turn)) else model.late
        val closed = model.copy(turn = model.turn.copy(active = null, replyOpen = false), late = late)
        return Transitions.maybeFinish(closed).after(listOf(LoopEffect.FlushMouth))
    }

    private fun failed(model: LoopModel, error: VoiceError): Step = when {
        error.code == VoiceErrorCode.CANCELLED -> close(model, delegate = false)
        model.outputActive -> Step(model.withTurn { copy(active = null, replyOpen = false, pendingFailure = error) })
        else -> Transitions.fail(model, error)
    }

    private fun interrupted(model: LoopModel, turn: TurnKey, signal: ReplySignal): Step = when (signal) {
        ReplySignal.Completed, is ReplySignal.Failed -> Step(release(model, turn))
        else -> Step(model)
    }

    private fun release(model: LoopModel, turn: TurnKey): LoopModel = model.withTurn {
        val remaining = interrupting - turn
        copy(interrupting = remaining, interruptUnresponsive = interruptUnresponsive && remaining.isNotEmpty())
    }
}

/** Text from a delegated answer: spoken when the person is quiet, held otherwise, or discarded by policy. */
internal object LateReplyRules {
    fun on(model: LoopModel, turn: TurnKey, signal: ReplySignal): Step = when (signal) {
        is ReplySignal.Segment -> lateSegment(model, signal.text)
        ReplySignal.Completed, is ReplySignal.Failed -> Step(model.copy(late = model.late.copy(delegated = model.late.delegated - turn)))
        else -> Step(model)
    }

    private fun lateSegment(model: LoopModel, text: VoiceText): Step = when {
        model.config.lateReplies == LateReplyPolicy.DISCARD -> Step(model)
        canSpeakLate(model) -> Transitions.speakAll(model, listOf(text))
        else -> Step(hold(model, text))
    }

    /** A late reply is spoken at once only when the loop is not answering and the person is not mid-utterance. */
    private fun canSpeakLate(model: LoopModel): Boolean = when (model.phase) {
        VoicePhase.LISTENING -> !model.turn.userSpeaking
        VoicePhase.IDLE -> true
        else -> false
    }

    private fun hold(model: LoopModel, text: VoiceText): LoopModel {
        val held = (model.late.held + text).takeLast(model.config.maxHeldLateSegments)
        return model.copy(late = model.late.copy(held = held))
    }
}
