package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.loop.machine.LoopEffect
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.ReplySignal
import com.letta.mobile.data.voice.loop.machine.TurnKey
import com.letta.mobile.data.voice.speech.SentenceChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One answer being followed: the responder's handle and the coroutine collecting it. */
private class FollowedReply(val reply: VoiceReply, val job: Job)

/**
 * Starts answers, follows each under its turn's generation token, and cancels them (bounded).
 * Used from the loop's processing coroutine.
 */
internal class ReplyFollower(private val wiring: LoopWiring) {
    private val replies = mutableMapOf<TurnKey, FollowedReply>()

    fun respond(effect: LoopEffect.Respond): LoopInput? {
        replies.entries.removeAll { !it.value.job.isActive }
        val reply = wiring.engines.responder.respond(effect.utterance)
        val job = wiring.scope.launch { follow(effect.turn, reply) }
        replies[effect.turn] = FollowedReply(reply, job)
        return null
    }

    /** Asks [turn]'s answer to stop without waiting; the outcome comes back as an input. */
    fun requestInterrupt(turn: TurnKey): LoopInput? {
        val followed = replies[turn] ?: return LoopInput.InterruptSettled(turn, VoiceCancelOutcome.ALREADY_FINISHED)
        wiring.scope.launch { wiring.inputs.send(LoopInput.InterruptSettled(turn, boundedCancel(followed.reply))) }
        return null
    }

    fun release(turn: TurnKey): LoopInput? {
        replies.remove(turn)?.job?.cancel()
        return null
    }

    /**
     * Cancels every answer, waiting (bounded) for each to settle. An answer that does not confirm
     * its stop turns into an UNRESPONSIVE responder fault, so a stop never reports idle over it.
     */
    suspend fun cancelAll(): LoopInput? {
        val all = replies.values.toList()
        replies.clear()
        val outcomes = all.map { followed ->
            boundedCancel(followed.reply).also { followed.job.cancel() }
        }
        return if (VoiceCancelOutcome.UNRESPONSIVE in outcomes) wiring.unresponsive(VoicePort.RESPONDER) else null
    }

    private suspend fun boundedCancel(reply: VoiceReply): VoiceCancelOutcome =
        withTimeoutOrNull(wiring.config.releaseTimeout) { reply.cancel() } ?: VoiceCancelOutcome.UNRESPONSIVE

    private suspend fun follow(turn: TurnKey, reply: VoiceReply) {
        val forwarder = ReplyForwarder(turn, SentenceChunker(wiring.config.maxSegmentLength), wiring.inputs)
        val outcome = runCatching { reply.events.collect(forwarder::forward) }
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        forwarder.finish(error != null)
    }
}

/** Chunks one answer's text into segments and forwards its events under its generation token. */
private class ReplyForwarder(
    private val turn: TurnKey,
    private val chunker: SentenceChunker,
    private val inputs: SendChannel<LoopInput>,
) {
    private var ended = false
    private var started = false

    suspend fun forward(event: VoiceReplyEvent) {
        when (event) {
            is VoiceReplyEvent.Text -> text(event)
            VoiceReplyEvent.PartEnded -> flush()
            VoiceReplyEvent.AwaitingLateReply -> flush(ReplySignal.Delegated)
            VoiceReplyEvent.Completed -> end(ReplySignal.Completed)
            is VoiceReplyEvent.Failed -> end(ReplySignal.Failed(event.error))
        }
    }

    /** The first non-blank delta ends the thinking wait even before a sentence is complete. */
    private suspend fun text(event: VoiceReplyEvent.Text) {
        if (!started && !event.delta.isBlank) {
            started = true
            send(listOf(ReplySignal.TextStarted))
        }
        send(chunker.push(event.delta).map(ReplySignal::Segment))
    }

    /** The answer's flow ended; [crashed] when it threw. An answer that just stopped counts as complete. */
    suspend fun finish(crashed: Boolean) {
        if (ended) return
        val failure = ReplySignal.Failed(VoiceError(VoiceErrorCode.ENGINE_FAILED, VoicePort.RESPONDER))
        end(if (crashed) failure else ReplySignal.Completed)
    }

    private suspend fun end(signal: ReplySignal) {
        ended = true
        flush(signal)
    }

    private suspend fun flush(then: ReplySignal? = null) {
        send(chunker.flush().map(ReplySignal::Segment) + listOfNotNull(then))
    }

    private suspend fun send(signals: List<ReplySignal>) {
        signals.forEach { inputs.send(LoopInput.FromReply(turn, it)) }
    }
}
