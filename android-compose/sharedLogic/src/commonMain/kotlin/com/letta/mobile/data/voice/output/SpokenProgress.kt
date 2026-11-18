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
 *
 * While a segment's audio is still arriving its duration is the longer of the audio received and
 * an estimate from its text: characters over the voice's speaking rate at the segment's speed. The
 * rate starts at a typical [DEFAULT_CHARACTERS_PER_SECOND] and is learned from every segment whose
 * audio has fully arrived (normalised to normal speed, so a speed change carries over), since a
 * voice's real rate differs and an estimate that is too long makes the highlight trail the audio.
 */
internal class SpokenProgressTracker(
    private val clock: () -> Long,
    private val speed: () -> SpeechSpeed,
) : SpeechAudioMeter {
    private val lock = SynchronizedObject()
    private val texts = mutableMapOf<SpeechSegmentId, VoiceText>()
    private val speeds = mutableMapOf<SpeechSegmentId, SpeechSpeed>()
    private var charactersPerSecond = DEFAULT_CHARACTERS_PER_SECOND
    private var calibrated = false
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
        if (complete) learnRate(id, audio)
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
        speeds[segment.id] = speed()
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
        speeds.remove(id)
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
        speeds.clear()
        audio.clear()
    }

    /** Learns the voice's rate at normal speed from segment [id], whose audio of [length] has fully arrived. */
    private fun learnRate(id: SpeechSegmentId, length: Duration) {
        val text = texts[id] ?: indices[id]?.let { mutableSegments.value.getOrNull(it)?.text } ?: return
        val seconds = length.inWholeMilliseconds / MILLIS_PER_SECOND
        if (seconds < MIN_CALIBRATION_SECONDS || text.value.isBlank()) return
        val atNormal = text.value.length / seconds / (speeds[id] ?: speed()).value
        val sample = atNormal.coerceIn(MIN_CHARACTERS_PER_SECOND, MAX_CHARACTERS_PER_SECOND)
        charactersPerSecond = if (calibrated) charactersPerSecond + (sample - charactersPerSecond) * LEARNING_RATE else sample
        calibrated = true
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
        val pace = (speeds[id] ?: speed()).value
        val estimate = text.value.length * MILLIS_PER_SECOND / (charactersPerSecond * pace)
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
        /** A typical speaking rate at normal speed, until the voice's own rate is learned. */
        const val DEFAULT_CHARACTERS_PER_SECOND = 15f
        const val MIN_CHARACTERS_PER_SECOND = 5f
        const val MAX_CHARACTERS_PER_SECOND = 40f

        /** How far each fully arrived segment moves the learned rate. */
        const val LEARNING_RATE = 0.5f

        /** Segments shorter than this say little about the rate. */
        const val MIN_CALIBRATION_SECONDS = 0.3f
        const val MILLIS_PER_SECOND = 1_000f
    }
}
