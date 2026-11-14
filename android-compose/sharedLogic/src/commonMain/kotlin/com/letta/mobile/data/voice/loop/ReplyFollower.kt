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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One answer being followed: the responder's handle and the coroutine collecting it. */
private class FollowedReply(val reply: VoiceReply, val job: Job)

/**
 * Starts answers, follows each under its turn's generation token, and cancels them (bounded).
 * Several answers may be followed at once (follow-ups); each is collected on its own.
 *
 * An answer is collected (which sends its utterance) only once every interrupt requested before it
 * has settled, each wait bounded by [com.letta.mobile.data.voice.VoiceLoopConfig.releaseTimeout]:
 * a stop of the answer the person spoke over then cannot reach the message they spoke next.
 * Used from the loop's processing coroutine.
 */
internal class ReplyFollower(private val wiring: LoopWiring) {
    private val replies = mutableMapOf<TurnKey, FollowedReply>()
    private val stopping = mutableListOf<Job>()

    /** Starts answering; a responder that throws fails that answer (ENGINE_FAILED), not the loop. */
    fun respond(effect: LoopEffect.Respond): LoopInput? {
        replies.entries.removeAll { !it.value.job.isActive }
        stopping.removeAll { !it.isActive }
        var reply: VoiceReply? = null
        wiring.call(VoicePort.RESPONDER) { reply = wiring.engines.responder.respond(effect.utterance) }
        val started = reply ?: return fail(effect.turn)
        val stops = stopping.toList()
        val job = wiring.scope.launch {
            awaitStops(stops)
            follow(effect.turn, started)
        }
        replies[effect.turn] = FollowedReply(started, job)
        return null
    }

    /** Asks [turn]'s answer to stop without waiting; the outcome comes back as an input. */
    fun requestInterrupt(turn: TurnKey): LoopInput? {
        val followed = replies[turn] ?: return LoopInput.InterruptSettled(turn, VoiceCancelOutcome.ALREADY_FINISHED)
        stopping += wiring.scope.launch { wiring.inputs.send(LoopInput.InterruptSettled(turn, boundedCancel(followed.reply))) }
        return null
    }

    /** Waits (bounded) until every stop requested before an answer has settled. */
    private suspend fun awaitStops(stops: List<Job>) {
        if (stops.isNotEmpty()) withTimeoutOrNull(wiring.config.releaseTimeout) { stops.joinAll() }
    }

    /**
     * Fails [turn]'s answer through the input queue (unbounded, so this never drops) rather than as
     * a fault of this step, which would hold back the step's other effects.
     */
    private fun fail(turn: TurnKey): LoopInput? {
        val error = VoiceError(VoiceErrorCode.ENGINE_FAILED, VoicePort.RESPONDER)
        wiring.inputs.trySend(LoopInput.FromReply(turn, ReplySignal.Failed(error)))
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

    /** A cancel that throws or misses the release budget is unconfirmed: UNRESPONSIVE. */
    private suspend fun boundedCancel(reply: VoiceReply): VoiceCancelOutcome {
        val outcome = runCatching { withTimeoutOrNull(wiring.config.releaseTimeout) { reply.cancel() } }
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        return outcome.getOrNull() ?: VoiceCancelOutcome.UNRESPONSIVE
    }

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
