package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechSkipReason
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
     * Speech over the answer being spoken interrupts it (barge-in). While the loop only waits for an
     * answer nothing is interrupted: the person is starting a follow-up, and answer text that
     * arrives meanwhile is held until they finish.
     */
    private fun speechStarted(model: LoopModel): Step = when (model.phase) {
        VoicePhase.SPEAKING -> Interrupts.bargeIn(model)
        VoicePhase.LISTENING, VoicePhase.THINKING ->
            Step(model.withTurn { copy(userSpeaking = true) }.withView { copy(retryable = null) })
        else -> Step(model)
    }

    private fun endOfTurn(model: LoopModel, utterance: VoiceUtterance): Step = when (model.phase) {
        VoicePhase.LISTENING, VoicePhase.THINKING -> Turns.start(model, utterance)
        VoicePhase.SPEAKING -> Interrupts.bargeIn(model).let { step -> Turns.start(step.model, utterance).after(step.effects) }
        else -> Step(model)
    }
}

/** The person's finished turns: each non-empty one is sent and its answer queued behind the others. */
internal object Turns {
    /** Starts answering [utterance] (nothing for an empty one), then lets the pending answers move on. */
    fun start(model: LoopModel, utterance: VoiceUtterance): Step {
        val heard = model.withTurn { copy(userSpeaking = false) }.withView { copy(transcript = utterance.text) }
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
            add(LoopEffect.StopMouth)
            addAll(skipped(SpeechSkipReason.MOUTH_FAILED, model.speech.outstanding.size))
            speaking?.let { add(LoopEffect.ReleaseReply(it.key)) }
        }
        val dropped = if (speaking == null) model else model.withTurn { without(speaking.key) }
        val reported = Recovery.report(dropped.silenced(), error)
        val next = if (model.phase == VoicePhase.SPEAKING) Transitions.maybeFinish(reported) else Step(reported)
        return next.after(effects)
    }
}
