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
import kotlinx.coroutines.launch

/**
 * A mouth that synthesises each segment through [synthesizer] and plays it through the platform's
 * [player]. The next segment is synthesised while the current one plays (one clip of prefetch),
 * which keeps gaps short when synthesis is faster than realtime. A synthesis failure reports
 * [VoiceMouthEvent.Failed] with the endpoint's code and drops the rest of the queue.
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

    /** One generation of queue, prefetch and playback; [stop] replaces it whole. */
    private inner class MouthPipeline {
        val texts = Channel<SpeechSegment>(Channel.UNLIMITED)
        private val clips = Channel<Pair<SpeechSegmentId, AudioClip>>(PREFETCH)
        val job: Job = scope.launch {
            launch { synthesizeAll() }
            playAll()
        }

        private suspend fun synthesizeAll() {
            for (segment in texts) {
                val clip = runCatching { synthesizer.synthesize(segment.text) }
                clip.exceptionOrNull()?.let { return report(it, VoiceErrorCode.SYNTHESIS_FAILED) }
                clips.send(segment.id to clip.getOrThrow())
            }
        }

        private suspend fun playAll() {
            for ((id, clip) in clips) {
                mutableEvents.emit(VoiceMouthEvent.Started(id))
                val played = runCatching { player.play(clip) { mutableEvents.tryEmit(VoiceMouthEvent.OutputLevel(it)) } }
                mutableEvents.emit(VoiceMouthEvent.OutputLevel(AudioLevel.Silent))
                played.exceptionOrNull()?.let { return report(it, VoiceErrorCode.PLAYBACK_FAILED) }
                mutableEvents.emit(VoiceMouthEvent.Finished(id))
            }
        }

        private suspend fun report(error: Throwable, fallback: VoiceErrorCode) {
            if (error is CancellationException) throw error
            val code = (error as? SpeechEndpointException)?.code ?: fallback
            mutableEvents.emit(VoiceMouthEvent.Failed(VoiceError(code, VoicePort.MOUTH)))
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val PREFETCH = 1
    }
}
