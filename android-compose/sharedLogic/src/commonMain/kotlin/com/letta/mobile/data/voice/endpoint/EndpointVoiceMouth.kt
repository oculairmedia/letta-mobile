package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioPlayer
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A mouth that synthesises each segment through [synthesizer] and plays it through the platform's
 * [player]. The next segment is synthesised while the current one plays (one clip of prefetch),
 * which keeps gaps short when synthesis is faster than realtime. A synthesis or playback failure
 * reports [VoiceMouthEvent.Failed] for its segment with the endpoint's code and drops the rest of the queue.
 *
 * Call [speak], [flush] and [stop] from one coroutine at a time (the loop's).
 */
class EndpointVoiceMouth(
    private val synthesizer: EndpointSpeechSynthesizer,
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
) : VoiceMouth {
    private val mutableEvents = MutableSharedFlow<VoiceMouthEvent>(extraBufferCapacity = EVENT_BUFFER)
    private var pipeline = MouthPipeline()

    override val events: SharedFlow<VoiceMouthEvent> = mutableEvents.asSharedFlow()

    override fun speak(segment: SpeechSegment) {
        pipeline.texts.trySend(segment)
    }

    /** Segments are synthesised as they arrive, so there is nothing buffered to push. */
    override fun flush() = Unit

    override suspend fun stop() {
        val stopping = pipeline
        pipeline = MouthPipeline()
        stopping.job.cancelAndJoin()
    }

    /** What failed in a pipeline, and the code to report when the error carries none. */
    private class PipelineFailure(val error: Throwable, val id: SpeechSegmentId, val fallback: VoiceErrorCode)

    /**
     * One generation of queue, prefetch and playback; [stop] replaces it whole. A failure in either
     * worker reports once, then cancels both workers and drops every pending segment and clip, so
     * nothing is synthesised or played after the failure.
     */
    private inner class MouthPipeline {
        val texts = Channel<SpeechSegment>(Channel.UNLIMITED)
        private val clips = Channel<Pair<SpeechSegmentId, AudioClip>>(PREFETCH)
        val job: Job = scope.launch {
            coroutineScope {
                launch { synthesizeAll(this@coroutineScope) }
                playAll(this)
            }
        }

        private suspend fun synthesizeAll(workers: CoroutineScope) {
            for (segment in texts) {
                val clip = runCatching { synthesizer.synthesize(segment.text) }
                val error = clip.exceptionOrNull()
                if (error != null) return report(workers, PipelineFailure(error, segment.id, VoiceErrorCode.SYNTHESIS_FAILED))
                clips.send(segment.id to clip.getOrThrow())
            }
        }

        private suspend fun playAll(workers: CoroutineScope) {
            for ((id, clip) in clips) {
                mutableEvents.emit(VoiceMouthEvent.Started(id))
                val played = runCatching { player.play(clip) { mutableEvents.tryEmit(VoiceMouthEvent.OutputLevel(it)) } }
                mutableEvents.tryEmit(VoiceMouthEvent.OutputLevel(AudioLevel.Silent))
                val error = played.exceptionOrNull()
                if (error != null) return report(workers, PipelineFailure(error, id, VoiceErrorCode.PLAYBACK_FAILED))
                mutableEvents.emit(VoiceMouthEvent.Finished(id))
            }
        }

        /**
         * Tears the pipeline down first, without suspending (both workers cancelled, pending text
         * and clips discarded), then reports. A subscriber too slow to take the event at once gets it
         * from a separate coroutine, so delivery can never hold up the teardown.
         */
        private fun report(workers: CoroutineScope, failure: PipelineFailure) {
            val error = failure.error
            if (error is CancellationException) throw error
            workers.cancel()
            texts.cancel()
            clips.cancel()
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
