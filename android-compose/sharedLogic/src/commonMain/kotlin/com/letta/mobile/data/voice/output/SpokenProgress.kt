package com.letta.mobile.data.voice.output

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.engine.SpeechAudioMeter
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * One segment of the reply being spoken, for a word-by-word highlight: the share of [text] spoken
 * so far is about `(now - startedAtMs) / audioDurationMs`, and all of it once [finishedAtMs] is set.
 * Times are milliseconds on [VoiceSessionControls.nowMs]'s clock.
 */
data class SpokenSegment(
    /** Its position in the current reply, from 0. */
    val index: Int,
    /** What is said. For display only: never log it. */
    val text: VoiceText,
    /** When its audio started playing. */
    val startedAtMs: Long,
    /**
     * How long its audio plays: estimated (from the audio received so far and the text's length)
     * while its audio is still arriving, exact once all of it has arrived; null when unknown.
     */
    val audioDurationMs: Long?,
    /** When it finished playing, or was stopped; null while it plays. */
    val finishedAtMs: Long?,
)

/** Who holds the current turn. */
enum class VoiceSpeaker {
    /** The person: speaking, or waiting for the answer to what they said. */
    USER,

    /** The agent's reply is being spoken. */
    AGENT,
}

/** The current turn: who holds it and since when, in milliseconds on [VoiceSessionControls.nowMs]'s clock. */
data class VoiceTurnClock(
    /** Who holds the turn. */
    val speaker: VoiceSpeaker,
    /** When the turn started. */
    val startedAtMs: Long,
) {
    /** How long the turn has lasted at [nowMs]. */
    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0)
}

/**
 * Follows the mouth of one voice session and publishes the current reply's [segments]: a segment
 * appears when it starts playing, its duration firms up as its audio arrives (through this
 * tracker as the mouth's [SpeechAudioMeter]), and it is closed when it finishes or the mouth is
 * stopped. [newReply] starts over (the person spoke). Safe from any thread.
 */
internal class SpokenProgressTracker(
    private val clock: () -> Long,
    private val speed: () -> SpeechSpeed,
) : SpeechAudioMeter {
    private val lock = SynchronizedObject()
    private val texts = mutableMapOf<SpeechSegmentId, VoiceText>()
    private val audio = mutableMapOf<SpeechSegmentId, SegmentAudio>()
    private val indices = mutableMapOf<SpeechSegmentId, Int>()
    private val mutableSegments = MutableStateFlow<List<SpokenSegment>>(emptyList())

    /** The segments of the current reply that have started playing, in order. */
    val segments: StateFlow<List<SpokenSegment>> = mutableSegments.asStateFlow()

    /** How much audio a segment has so far. */
    private data class SegmentAudio(val length: Duration, val complete: Boolean)

    /** [mouth], reporting what it speaks to this tracker. */
    fun track(mouth: VoiceMouth): VoiceMouth = ProgressMouth(mouth)

    override fun measured(id: SpeechSegmentId, audio: Duration, complete: Boolean) = synchronized(lock) {
        this.audio[id] = SegmentAudio(audio, complete)
        val index = indices[id] ?: return@synchronized
        edit(index) { it.copy(audioDurationMs = durationMs(it.text, id)) }
    }

    /** Clears the current reply: the person started a new turn, or the session ended. */
    fun newReply() = synchronized(lock) {
        texts.clear()
        audio.clear()
        indices.clear()
        mutableSegments.value = emptyList()
    }

    private fun queued(segment: SpeechSegment) = synchronized(lock) {
        texts[segment.id] = segment.text
    }

    private fun started(id: SpeechSegmentId) = synchronized(lock) {
        val text = texts.remove(id) ?: return@synchronized
        val index = mutableSegments.value.size
        indices[id] = index
        mutableSegments.value += SpokenSegment(index, text, clock(), durationMs(text, id), finishedAtMs = null)
    }

    private fun finished(id: SpeechSegmentId) = synchronized(lock) {
        val index = indices.remove(id) ?: return@synchronized
        val now = clock()
        val known = audio.remove(id)?.takeIf { it.complete }
        edit(index) { segment ->
            val played = now - segment.startedAtMs
            segment.copy(audioDurationMs = known?.length?.inWholeMilliseconds ?: played, finishedAtMs = now)
        }
    }

    /** The mouth stopped: whatever was playing ends now and nothing queued will start. */
    private fun stopped() = synchronized(lock) {
        val now = clock()
        indices.values.forEach { index -> edit(index) { it.copy(finishedAtMs = now) } }
        indices.clear()
        texts.clear()
        audio.clear()
    }

    private fun edit(index: Int, change: (SpokenSegment) -> SpokenSegment) {
        val current = mutableSegments.value
        if (index !in current.indices) return
        mutableSegments.value = current.toMutableList().also { it[index] = change(it[index]) }
    }

    /** Exact once all audio arrived; before that the longer of the audio so far and the text's estimate. */
    private fun durationMs(text: VoiceText, id: SpeechSegmentId): Long {
        val known = audio[id]
        val received = known?.length?.inWholeMilliseconds ?: 0
        if (known?.complete == true) return received
        val estimate = text.value.length * MILLIS_PER_SECOND / (CHARACTERS_PER_SECOND * speed().value)
        return maxOf(received, estimate.roundToLong())
    }

    /** A mouth whose segments, starts, ends and stops this tracker follows. */
    private inner class ProgressMouth(private val inner: VoiceMouth) : VoiceMouth by inner {
        override val events: Flow<VoiceMouthEvent> = inner.events.onEach { event ->
            when (event) {
                is VoiceMouthEvent.Started -> started(event.id)
                is VoiceMouthEvent.Finished -> finished(event.id)
                is VoiceMouthEvent.Failed -> finished(event.id)
                is VoiceMouthEvent.OutputLevel -> Unit
            }
        }

        override fun speak(segment: SpeechSegment) {
            queued(segment)
            inner.speak(segment)
        }

        override suspend fun stop() {
            try {
                inner.stop()
            } finally {
                stopped()
            }
        }
    }

    private companion object {
        /** A typical speaking rate at normal speed, for the estimate before the audio has arrived. */
        const val CHARACTERS_PER_SECOND = 15f
        const val MILLIS_PER_SECOND = 1_000f
    }
}
