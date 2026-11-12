package com.letta.mobile.data.voice.engine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceText
import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

/** The loop's number for one spoken segment. */
@JvmInline
value class SpeechSegmentId(
    /** The number, increasing within one loop. */
    val value: Long,
)

/** One piece of text to speak, usually a sentence. */
data class SpeechSegment(
    /** Its number. */
    val id: SpeechSegmentId,
    /** What to say. */
    val text: VoiceText,
)

/**
 * Speaking: speaks segments in the order given.
 *
 * Every segment that is spoken reports [VoiceMouthEvent.Started] then [VoiceMouthEvent.Finished];
 * a segment dropped by [stop] reports neither.
 */
interface VoiceMouth {
    /** What the mouth reports; hot, collected for the loop's lifetime. */
    val events: Flow<VoiceMouthEvent>

    /** Queues [segment] behind anything already queued. */
    fun speak(segment: SpeechSegment)

    /** No more segments are coming for now: synthesise and play whatever is buffered. */
    fun flush()

    /**
     * Stops speaking at once and drops the queue (barge-in). Suspends until playback has actually
     * stopped and the output device is released; the loop bounds the wait.
     */
    suspend fun stop()
}

/** What a mouth reports. */
sealed interface VoiceMouthEvent {
    /** Segment [id] started playing. */
    data class Started(
        /** The segment. */
        val id: SpeechSegmentId,
    ) : VoiceMouthEvent

    /** Segment [id] finished playing. */
    data class Finished(
        /** The segment. */
        val id: SpeechSegmentId,
    ) : VoiceMouthEvent

    /** The output [level], for the avatar's mouth. */
    data class OutputLevel(
        /** The current loudness. */
        val level: AudioLevel,
    ) : VoiceMouthEvent

    /**
     * Speaking segment [id] failed; the mouth has dropped the rest of its queue. The loop ignores a
     * failure for a segment it no longer has outstanding (output it already stopped).
     */
    data class Failed(
        /** What failed. */
        val error: VoiceError,
        /** The segment that failed. */
        val id: SpeechSegmentId,
    ) : VoiceMouthEvent
}
