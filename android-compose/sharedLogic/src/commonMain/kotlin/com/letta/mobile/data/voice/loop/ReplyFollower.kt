package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.loop.machine.LoopEffect
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.ReplySignal
import com.letta.mobile.data.voice.loop.machine.TurnKey
import com.letta.mobile.data.voice.speech.SentenceChunker
import com.letta.mobile.data.voice.speech.batchSegments
import com.letta.mobile.data.voice.tone.ToneCue
import com.letta.mobile.data.voice.tone.ToneCueStream
import com.letta.mobile.data.voice.tone.ToneCues
import com.letta.mobile.data.voice.tone.TonePiece
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
        val chunker = SentenceChunker(wiring.config.maxSegmentLength, wiring.config.firstClauseMinLength, wiring.config.minSegmentLength)
        val forwarder = ReplyForwarder(turn, chunker, wiring.config.maxSegmentLength, wiring.inputs, wiring.observer)
        val outcome = runCatching { reply.events.collect(forwarder::forward) }
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        forwarder.finish(error != null)
    }
}

/**
 * Chunks one answer's text into segments and forwards its events under its generation token. Each
 * segment goes out the moment the chunker releases it; segments released together (text that
 * arrived as a burst) are joined up to [maxSegmentLength], except the answer's first segment, which
 * always goes alone so first audio never waits for a batch. Tone cues are read out of the text
 * first ([ToneCueStream]): no cue is ever spoken, and each segment carries the cue in force for it
 * in the cue syntax ([ToneCues.tag]), which the loop reads back when it hands the segment to the
 * mouth. A cue takes effect at the next segment boundary (the chunker is flushed first, so no
 * segment, merged or not, spans two moods) and the mood stays until the next cue: only an explicit
 * `[neutral]` clears it. When the answer ends, its segment shape goes to the [observer]
 * ([ReplyShape]).
 */
private class ReplyForwarder(
    private val turn: TurnKey,
    private val chunker: SentenceChunker,
    private val maxSegmentLength: Int,
    private val inputs: SendChannel<LoopInput>,
    private val observer: VoiceLoopObserver = VoiceLoopObserver.None,
) {
    private val shape = ReplyShape()
    private var ended = false
    private var started = false
    private var segmented = false
    private val tones = ToneCueStream()
    private var tone: ToneCue? = null

    suspend fun forward(event: VoiceReplyEvent) {
        when (event) {
            is VoiceReplyEvent.Text -> text(event)
            VoiceReplyEvent.PartEnded -> flush()
            VoiceReplyEvent.AwaitingLateReply -> flush(ReplySignal.Delegated).also { chunker.startNewPart() }
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
        send(spoken(tones.push(event.delta.value)))
    }

    /**
     * The reply's words go to the chunker; a change of tone first releases what is pending (it was
     * said in the tone before), so every segment carries exactly the tone in force for it.
     */
    private fun spoken(pieces: List<TonePiece>): List<ReplySignal> = pieces.flatMap { piece ->
        when (piece) {
            is TonePiece.Words -> segments(chunker.push(VoiceText(piece.text)))
            is TonePiece.Cue -> segments(chunker.flush()).also { tone = piece.cue }
        }
    }

    /** The answer's flow ended; [crashed] when it threw. An answer that just stopped counts as complete. */
    suspend fun finish(crashed: Boolean) {
        if (ended) return
        val failure = ReplySignal.Failed(VoiceError(VoiceErrorCode.ENGINE_FAILED, VoicePort.RESPONDER))
        end(if (crashed) failure else ReplySignal.Completed)
    }

    private suspend fun end(signal: ReplySignal) {
        ended = true
        send(spoken(tones.finish()))
        flush(signal)
        report()
    }

    /** Tells the observer how the answer was segmented; an observer that throws is ignored (diagnostics never stop the loop). */
    private fun report() {
        val error = runCatching { shape.report(observer) }.exceptionOrNull()
        if (error is CancellationException) throw error
    }

    private suspend fun flush(then: ReplySignal? = null) {
        send(segments(chunker.flush()) + listOfNotNull(then))
    }

    private fun segments(released: List<VoiceText>): List<ReplySignal> {
        val batches = batchSegments(released, firstAlone = !segmented, maxLength = maxSegmentLength)
        if (batches.isNotEmpty()) segmented = true
        return batches.map {
            shape.add(it.value.length, tone)
            ReplySignal.Segment(VoiceText(ToneCues.tag(tone, it.value)))
        }
    }

    private suspend fun send(signals: List<ReplySignal>) {
        signals.forEach { inputs.send(LoopInput.FromReply(turn, it)) }
    }
}

/**
 * The segments one answer was spoken in: how many, how long (characters, words only) and how often
 * the tone changed between neighbouring segments. Counts only, never text.
 */
private class ReplyShape {
    private val lengths = mutableListOf<Int>()
    private val tones = mutableListOf<ToneCue?>()

    fun add(length: Int, tone: ToneCue?) {
        lengths += length
        tones += tone
    }

    /** Tells [observer] the shape; an answer with no segment reports nothing. */
    fun report(observer: VoiceLoopObserver) {
        if (lengths.isEmpty()) return
        val sorted = lengths.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        val moodChanges = tones.zipWithNext().count { (before, after) -> before != after }
        observer.replySegmented(sorted.size, sorted.first(), median, moodChanges)
    }
}
