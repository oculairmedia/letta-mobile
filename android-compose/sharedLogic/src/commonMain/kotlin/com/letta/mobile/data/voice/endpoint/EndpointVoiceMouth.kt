package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A mouth that synthesises each segment through [synthesizer] and plays it through the platform's
 * [player] while it streams in: playback of a sentence starts with its first audio, not after its
 * whole response. The next segment's request starts as soon as the current one's response is
 * complete, so it downloads while the current one plays (one sentence ahead, requests one at a
 * time); the one after waits until that next segment starts playing. A segment is
 * [VoiceMouthEvent.Finished] only once the player has played it out. A synthesis or playback
 * failure reports [VoiceMouthEvent.Failed] for its segment with the endpoint's code and drops the
 * rest of the queue.
 *
 * The pipeline (its two workers in [scope]) starts with the first segment after construction or
 * after a stop, and [stop] ends it without starting another: it cuts playback, closes the request
 * in flight and leaves no coroutine running, so stopping is also the mouth's final teardown.
 *
 * Call [speak], [flush] and [stop] from one coroutine at a time (the loop's).
 */
class EndpointVoiceMouth(
    private val synthesizer: EndpointSpeechSynthesizer,
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
) : VoiceMouth {
    private val mutableEvents = MutableSharedFlow<VoiceMouthEvent>(extraBufferCapacity = EVENT_BUFFER)
    private var pipeline: MouthPipeline? = null

    override val events: SharedFlow<VoiceMouthEvent> = mutableEvents.asSharedFlow()

    override fun speak(segment: SpeechSegment) {
        val current = pipeline ?: MouthPipeline().also { pipeline = it }
        current.texts.trySend(segment)
    }

    /** Segments are synthesised as they arrive, so there is nothing buffered to push. */
    override fun flush() = Unit

    /**
     * Probe for tests: true when the event buffer cannot take another event right now (a subscriber
     * is behind). When it can, a silent output level is emitted, which is harmless.
     */
    internal fun eventBufferFull(): Boolean = !mutableEvents.tryEmit(VoiceMouthEvent.OutputLevel(AudioLevel.Silent))

    /** Ends the current pipeline, if any, and waits until its workers have finished; starts nothing. */
    override suspend fun stop() {
        val stopping = pipeline ?: return
        pipeline = null
        stopping.job.cancelAndJoin()
    }

    /** What failed in a pipeline, and the code to report when the error carries none. */
    private class PipelineFailure(val error: Throwable, val id: SpeechSegmentId, val fallback: VoiceErrorCode)

    /** One segment's audio as it streams in; its player takes [chunks] while they are still arriving. */
    private class SegmentAudio(val id: SpeechSegmentId) {
        // overflow: one sentence's audio (its text is capped by the chunker), consumed as it plays; dropped with the pipeline.
        val chunks = Channel<PcmChunk>(Channel.UNLIMITED)
    }

    /**
     * One generation of queue, prefetch and playback; [stop] ends it whole. A failure in either
     * worker reports once, then cancels both workers (closing the request in flight and cutting
     * playback) and drops every pending segment, so nothing is synthesised or played after it.
     */
    private inner class MouthPipeline {
        // overflow: holds only the segments of the reply being spoken (bounded by that reply's length); stop() and failures discard it.
        val texts = Channel<SpeechSegment>(Channel.UNLIMITED)
        private val streams = Channel<SegmentAudio>(PREFETCH)
        val job: Job = scope.launch {
            coroutineScope {
                launch { synthesizeAll(this@coroutineScope) }
                playAll(this)
            }
        }

        /**
         * Hands each segment to the player before its request starts, then streams the response into
         * it. With one segment waiting in [streams], the request for the segment after the playing one
         * runs while it plays, and the next hand-off waits until that segment starts playing.
         */
        private suspend fun synthesizeAll(workers: CoroutineScope) {
            for (segment in texts) {
                val audio = SegmentAudio(segment.id)
                streams.send(audio)
                // A player that stopped taking this segment's audio has cancelled its channel; the rest is dropped.
                val streamed = runCatching { synthesizer.stream(segment.text).collect { audio.chunks.trySend(it) } }
                val error = streamed.exceptionOrNull()
                if (error != null) return report(workers, PipelineFailure(error, segment.id, VoiceErrorCode.SYNTHESIS_FAILED))
                audio.chunks.close()
            }
        }

        /** Plays each segment as it streams in; it has started once its first audio is there to play. */
        private suspend fun playAll(workers: CoroutineScope) {
            for (audio in streams) {
                val first = audio.chunks.receiveCatching().getOrNull()
                mutableEvents.emit(VoiceMouthEvent.Started(audio.id))
                val chunks = flow {
                    first?.let { emit(it) }
                    emitAll(audio.chunks)
                }
                val played = runCatching { player.play(chunks) { mutableEvents.tryEmit(VoiceMouthEvent.OutputLevel(it)) } }
                mutableEvents.tryEmit(VoiceMouthEvent.OutputLevel(AudioLevel.Silent))
                val error = played.exceptionOrNull()
                if (error != null) return report(workers, PipelineFailure(error, audio.id, VoiceErrorCode.PLAYBACK_FAILED))
                mutableEvents.emit(VoiceMouthEvent.Finished(audio.id))
            }
        }

        /**
         * Tears the pipeline down first, without suspending (both workers cancelled, pending text
         * and audio discarded), then reports. A subscriber too slow to take the event at once gets it
         * from a separate coroutine, so delivery can never hold up the teardown.
         */
        private fun report(workers: CoroutineScope, failure: PipelineFailure) {
            val error = failure.error
            if (error is CancellationException) throw error
            workers.cancel()
            texts.cancel()
            streams.cancel()
            val code = (error as? SpeechEndpointException)?.code ?: failure.fallback
            val failed = VoiceMouthEvent.Failed(VoiceError(code, VoicePort.MOUTH), failure.id)
            if (!mutableEvents.tryEmit(failed)) scope.launch { mutableEvents.emit(failed) }
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val PREFETCH = 1
    }
}
