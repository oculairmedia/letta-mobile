package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
import com.letta.mobile.data.voice.VoiceDecision
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent

/** What the ear reports. Its failures stop the loop: without the microphone there is nothing to go on with. */
internal object EarRules {
    fun on(model: LoopModel, event: VoiceEarEvent): Step = when (event) {
        VoiceEarEvent.ListeningStarted -> listening(model)
        is VoiceEarEvent.InputLevel -> Step(model.withView { copy(inputLevel = event.level) })
        is VoiceEarEvent.PartialTranscript -> Step(model.withView { copy(transcript = event.text) })
        VoiceEarEvent.SpeechStarted -> speechStarted(model)
        VoiceEarEvent.SpeechEnded -> speechEnded(model)
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

    /**
     * Speech over the answer being spoken interrupts it (barge-in), unless interrupting by voice is
     * off (half-duplex: the ear should not report speech over the output at all, and if it does, it
     * is not the person to be trusted over the app's own echo). While the loop only waits for an
     * answer nothing is interrupted: the person is starting a follow-up, and answer text that
     * arrives meanwhile is held until their turn closes.
     */
    private fun speechStarted(model: LoopModel): Step = when (model.phase) {
        VoicePhase.SPEAKING -> if (model.config.voiceInterrupts) Interrupts.bargeIn(model) else Step(model)
        VoicePhase.LISTENING, VoicePhase.THINKING ->
            Step(model.withTurn { copy(userSpeaking = true) }.withView { copy(retryable = null) })
        else -> Step(model)
    }

    /**
     * The person's turn closed; only its transcript is still to come. Held answer text is released
     * now rather than when the transcript arrives: a short sound that opened a turn must not hold a
     * finished answer back for the length of a transcription. If the turn was real speech, its
     * transcript arrives as a follow-up and is answered after.
     */
    private fun speechEnded(model: LoopModel): Step {
        if (!model.turn.userSpeaking) return Step(model)
        val ended = model.withTurn { copy(userSpeaking = false, transcribing = transcribing + 1) }
        val waiting = ended.turn.head != null || ended.late.held.isNotEmpty()
        val answering = ended.phase == VoicePhase.LISTENING || ended.phase == VoicePhase.THINKING
        return if (waiting && answering) Transitions.maybeFinish(ended) else Step(ended)
    }

    /**
     * A transcript arrived. While the app speaks it is a follow-up (its turn closed before the
     * speech started) and is answered after; only an end of turn the loop never saw start, from an
     * ear that reports no start, still interrupts by voice where that is on.
     */
    private fun endOfTurn(model: LoopModel, utterance: VoiceUtterance): Step {
        val unannounced = !model.turn.userSpeaking && model.turn.transcribing == 0
        val heard = model.withTurn { if (transcribing > 0) copy(transcribing = transcribing - 1) else copy(userSpeaking = false) }
        return when (model.phase) {
            VoicePhase.LISTENING, VoicePhase.THINKING -> Turns.start(heard, utterance)
            VoicePhase.SPEAKING -> if (model.config.voiceInterrupts && unannounced) {
                Interrupts.bargeIn(heard).let { step -> Turns.start(step.model.withTurn { copy(userSpeaking = false) }, utterance).after(step.effects) }
            } else {
                Turns.start(heard, utterance)
            }
            else -> Step(model)
        }
    }
}

/** The person's finished turns: each non-empty one is sent and its answer queued behind the others. */
internal object Turns {
    /** Starts answering [utterance] (nothing for an empty one), then lets the pending answers move on. */
    fun start(model: LoopModel, utterance: VoiceUtterance): Step {
        val heard = model.withView { copy(transcript = utterance.text) }
        if (utterance.isEmpty) return Transitions.maybeFinish(heard)
        val key = TurnKey(heard.turn.nextKey)
        val answered = utterance.decision != VoiceDecision.AnsweredByEar
        val reply = PendingReply(key, open = answered, held = listOfNotNull(utterance.immediateReply), utterance = utterance)
        val bounded = makeRoom(heard.withView { copy(turnFailure = null, retryUtterance = null) })
        val queued = bounded.model.withTurn { copy(replies = replies + reply, nextKey = nextKey + 1) }
        val respond = if (answered) listOf(LoopEffect.Respond(key, utterance)) else emptyList()
        return Transitions.maybeFinish(queued).after(bounded.effects + respond)
    }

    /**
     * At the pending limit, the oldest answer that has not started speaking is dropped: it is no
     * longer followed (its run goes on in the conversation) and nothing of it is spoken.
     */
    private fun makeRoom(model: LoopModel): Step {
        val replies = model.turn.replies
        if (replies.size < model.config.maxPendingReplies) return Step(model)
        val victim = replies.firstOrNull { it.key != model.speech.owner } ?: return Step(model)
        val effects = listOf(
            LoopEffect.ReleaseReply(victim.key),
            LoopEffect.SpeechSkipped(SpeechSkipReason.PENDING_LIMIT, maxOf(victim.held.size, 1)),
        )
        return Step(model.withTurn { without(victim.key) }, effects)
    }
}

/** What the mouth reports. */
internal object MouthRules {
    fun on(model: LoopModel, event: VoiceMouthEvent): Step = when (event) {
        is VoiceMouthEvent.Started -> Step(model.withView { copy(speaking = model.speech.outstanding[event.id] ?: speaking) })
        is VoiceMouthEvent.Finished -> finished(model, event)
        is VoiceMouthEvent.OutputLevel -> level(model, event)
        is VoiceMouthEvent.Failed -> if (event.id in model.speech.outstanding) failed(model, event.error) else Step(model)
    }

    private fun level(model: LoopModel, event: VoiceMouthEvent.OutputLevel): Step =
        if (model.outputActive) Step(model.withView { copy(outputLevel = event.level) }) else Step(model)

    private fun finished(model: LoopModel, event: VoiceMouthEvent.Finished): Step {
        if (event.id !in model.speech.outstanding) return Step(model)
        val remaining = model.speech.outstanding - event.id
        val owner = model.speech.owner.takeIf { remaining.isNotEmpty() }
        val drained = model.copy(
            speech = model.speech.copy(outstanding = remaining, owner = owner),
            view = model.view.copy(speaking = null, outputLevel = AudioLevel.Silent),
        )
        return if (drained.phase == VoicePhase.SPEAKING) Transitions.maybeFinish(drained) else Step(drained)
    }

    /**
     * Speaking failed (the mouth has dropped its queue): the mouth is reset, the rest of the answer
     * being spoken is dropped (no longer followed; its run goes on in the conversation), the failure
     * is reported and the next answer takes its turn.
     */
    private fun failed(model: LoopModel, error: VoiceError): Step {
        val speaking = model.speech.owner?.let(model.turn::find)
        val effects = buildList {
            add(LoopEffect.StopMouth(SpeechStopReason.MOUTH_FAILED))
            addAll(skipped(SpeechSkipReason.MOUTH_FAILED, model.speech.outstanding.size))
            speaking?.let { add(LoopEffect.ReleaseReply(it.key)) }
        }
        val dropped = if (speaking == null) model else model.withTurn { without(speaking.key) }
        val reported = Recovery.report(dropped.silenced(), error)
        val next = if (model.phase == VoicePhase.SPEAKING) Transitions.maybeFinish(reported) else Step(reported)
        return next.after(effects)
    }
}
