package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.LateReplyPolicy
import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome

/**
 * What answers report, routed by generation token: the head answer's text is spoken (or held
 * while it cannot be), a queued answer's text is held until its turn, an interrupted turn's text
 * is dropped, a delegated turn's text is a late reply, and any other token is stale and dropped.
 */
internal object ReplyRules {
    fun on(model: LoopModel, turn: TurnKey, signal: ReplySignal): Step = when {
        turn == model.turn.active -> HeadReplyRules.on(model, signal)
        model.turn.find(turn) != null -> QueuedReplyRules.on(model, turn, signal)
        turn in model.turn.interrupting -> interrupted(model, turn, signal)
        turn in model.late.delegated -> LateReplyRules.on(model, turn, signal)
        else -> Step(model)
    }

    /** A requested interrupt of [turn] settled. */
    fun settled(model: LoopModel, turn: TurnKey, outcome: VoiceCancelOutcome): Step {
        if (turn !in model.turn.interrupting) return Step(model)
        if (outcome == VoiceCancelOutcome.UNRESPONSIVE) return Step(model.withTurn { copy(interruptUnresponsive = true) })
        return Step(release(model, turn), listOf(LoopEffect.ReleaseReply(turn)))
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

/** The head answer: the one spoken now, or next once nothing else plays and the person is quiet. */
internal object HeadReplyRules {
    fun on(model: LoopModel, signal: ReplySignal): Step {
        val head = checkNotNull(model.turn.head)
        return when (signal) {
            is ReplySignal.Segment -> segment(model, head, signal.text)
            ReplySignal.TextStarted -> Step(model.withTurn { update(head.key) { copy(textArrived = true) } })
            ReplySignal.Delegated -> close(model, head, delegate = true)
            ReplySignal.Completed -> close(model, head, delegate = false)
            is ReplySignal.Failed -> failed(model, head, signal.error)
            ReplySignal.ThinkingTimedOut -> timedOut(model, head)
        }
    }

    /**
     * Spoken at once while the loop is answering, the person is quiet and no other answer's speech
     * is still playing; held on the answer otherwise, and spoken when it gets its turn.
     */
    private fun segment(model: LoopModel, head: PendingReply, text: VoiceText): Step {
        if (!canSpeak(model, head)) return Step(model.withTurn { update(head.key) { copy(held = held + text) } })
        val owned = model.copy(phase = VoicePhase.SPEAKING, speech = model.speech.copy(owner = head.key))
        return Transitions.speak(owned, text)
    }

    private fun canSpeak(model: LoopModel, head: PendingReply): Boolean {
        val answering = model.phase == VoicePhase.THINKING || model.phase == VoicePhase.SPEAKING
        val free = !model.outputActive || model.speech.owner == head.key
        return answering && free && !model.turn.userSpeaking
    }

    /**
     * The head answer has nothing more to say (or delegated the rest): it leaves the queue unless
     * it still holds text, which is spoken when it gets its turn; the next answer follows once the
     * current speech has played.
     */
    private fun close(model: LoopModel, head: PendingReply, delegate: Boolean): Step {
        val late = if (delegate) model.late.copy(delegated = model.late.delegated + head.key) else model.late
        val turn = if (head.held.isEmpty()) model.turn.without(head.key) else model.turn.update(head.key) { copy(open = false) }
        return Transitions.maybeFinish(model.copy(turn = turn, late = late)).after(listOf(LoopEffect.FlushMouth))
    }

    private fun failed(model: LoopModel, head: PendingReply, error: VoiceError): Step =
        if (error.code == VoiceErrorCode.CANCELLED) close(model, head, delegate = false) else Recovery.replyFailed(model, head, error)

    /**
     * The first-text timeout applies to an open head answer that has produced no text at all, in
     * whatever phase (an immediate reply may have been spoken meanwhile).
     */
    private fun timedOut(model: LoopModel, head: PendingReply): Step =
        if (head.open && !head.textArrived) Recovery.timedOut(model, head) else Step(model)
}

/** An answer queued behind the head: everything it says waits for its turn. */
internal object QueuedReplyRules {
    fun on(model: LoopModel, turn: TurnKey, signal: ReplySignal): Step {
        val reply = checkNotNull(model.turn.find(turn))
        return when (signal) {
            is ReplySignal.Segment -> Step(model.withTurn { update(turn) { copy(held = held + signal.text) } })
            ReplySignal.TextStarted -> Step(model.withTurn { update(turn) { copy(textArrived = true) } })
            ReplySignal.Delegated -> Step(closed(model, turn).copy(late = model.late.copy(delegated = model.late.delegated + turn)))
            ReplySignal.Completed -> Step(closed(model, turn))
            is ReplySignal.Failed -> failed(model, reply, signal.error)
            ReplySignal.ThinkingTimedOut -> Step(model)
        }
    }

    private fun closed(model: LoopModel, turn: TurnKey): LoopModel = model.withTurn { update(turn) { copy(open = false) } }

    private fun failed(model: LoopModel, reply: PendingReply, error: VoiceError): Step =
        if (error.code == VoiceErrorCode.CANCELLED) Step(closed(model, reply.key)) else Recovery.replyFailed(model, reply, error)
}

/** Failures of one answer: reported, and the loop goes on with the next answer or listens. */
internal object Recovery {
    /**
     * [reply] failed: it leaves the queue with the text it held, the failure is reported (with the
     * utterance kept for a retry when one makes sense), and speech already playing finishes.
     */
    fun replyFailed(model: LoopModel, reply: PendingReply, error: VoiceError): Step {
        val wasHead = reply.key == model.turn.active
        val reported = report(model.withTurn { without(reply.key) }, error, reply)
        val effects = skipped(SpeechSkipReason.REPLY_FAILED, reply.held.size)
        if (!wasHead) return Step(reported, effects)
        return Transitions.maybeFinish(reported).after(effects + LoopEffect.FlushMouth)
    }

    /**
     * The head answer produced no text in time: its run is asked to stop, any immediate reply of
     * its own still playing is stopped, the failure is reported and the next answer takes its turn.
     */
    fun timedOut(model: LoopModel, head: PendingReply): Step {
        val ownSpeech = model.outputActive && model.speech.owner == head.key
        val effects = buildList {
            if (ownSpeech) add(LoopEffect.StopMouth(SpeechStopReason.TIMED_OUT))
            add(LoopEffect.RequestInterrupt(head.key))
        }
        val stopped = model.withTurn { without(head.key).copy(interrupting = interrupting + head.key) }
        val quiet = if (ownSpeech) stopped.silenced() else stopped
        val reported = report(quiet, VoiceError(VoiceErrorCode.TIMED_OUT, VoicePort.RESPONDER), head)
        return Transitions.maybeFinish(reported).after(effects)
    }

    /** [model] with [error] reported for [reply]'s utterance. */
    fun report(model: LoopModel, error: VoiceError, reply: PendingReply? = null): LoopModel {
        val retry = reply?.utterance?.takeIf { error.action == VoiceUserAction.RETRY && error.port == VoicePort.RESPONDER }
        return model.withView { copy(turnFailure = error, retryUtterance = retry) }
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

    /** A late reply is spoken at once only when nothing is pending, the loop is not answering and the person is not mid-utterance. */
    private fun canSpeakLate(model: LoopModel): Boolean = when {
        model.turn.replies.isNotEmpty() -> false
        model.phase == VoicePhase.LISTENING -> !model.turn.userSpeaking
        else -> model.phase == VoicePhase.IDLE
    }

    private fun hold(model: LoopModel, text: VoiceText): LoopModel {
        val held = (model.late.held + text).takeLast(model.config.maxHeldLateSegments)
        return model.copy(late = model.late.copy(held = held))
    }
}
