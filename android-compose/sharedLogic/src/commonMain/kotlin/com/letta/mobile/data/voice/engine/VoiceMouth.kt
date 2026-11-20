package com.letta.mobile.data.voice.engine

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.tone.ToneCue
import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline
import kotlin.time.Duration

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
    /** What to say, with no tone cue in it. */
    val text: VoiceText,
    /** How to say it: the reply's tone cue in force for this segment, sent as `instructions`; null for none. */
    val tone: ToneCue? = null,
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

/**
 * Told how much audio a mouth has for each segment as it arrives: the playing time of the PCM
 * received so far, and whether all of it has arrived. Never given the audio or the text.
 */
fun interface SpeechAudioMeter {
    /** Segment [id] has [audio] of sound so far; [complete] once its whole response has arrived. */
    fun measured(id: SpeechSegmentId, audio: Duration, complete: Boolean)

    /** A meter that ignores everything. */
    companion object {
        /** Ignores everything. */
        val None: SpeechAudioMeter = SpeechAudioMeter { _, _, _ -> }
    }
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
