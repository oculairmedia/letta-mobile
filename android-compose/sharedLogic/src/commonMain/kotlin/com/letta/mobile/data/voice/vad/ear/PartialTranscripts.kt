package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Live partial transcripts for the server ear: while a confirmed turn is being heard, the turn's
 * audio so far (at most its last [window]) is transcribed every [interval] of audio, and each
 * result is reported as [VoiceEarEvent.PartialTranscript]. Off unless [enabled]: it sends the
 * person's audio to the transcription endpoint several times per turn.
 */
data class LivePartials(
    /** Whether partials are requested at all. */
    val enabled: Boolean = false,
    /** Audio between two partial requests. */
    val interval: Duration = 600.milliseconds,
    /** The most audio sent with one partial request: the end of the turn so far. */
    val window: Duration = 20.seconds,
) {
    init {
        require(interval.isPositive()) { "Partial interval must be positive" }
        require(window.isPositive()) { "Partial window must be positive" }
    }
}

/**
 * Drives [LivePartials] for one listen. All timing is audio time (sample positions). At most one
 * partial request is in flight: a tick that comes while one runs is skipped. A response is shown
 * only if its turn is still the current one and no newer response was shown; ending or dropping
 * the turn cancels the request in flight. Partials never become the turn's utterance: the
 * whole-turn transcription at its end stays authoritative. A failed partial is ignored silently.
 * Partial audio is wiped once sent; nothing about a partial is logged.
 */
internal class PartialTranscripts(
    private val settings: LivePartials,
    private val format: AudioFrameFormat,
    private val transcriber: SpeechTranscriber,
    private val out: ProducerScope<VoiceEarEvent>,
) {
    private val interval = samplesOf(settings.interval)
    private val window = samplesOf(settings.window)
    private val lock = SynchronizedObject()
    private var nextTick: Long? = null
    private var job: Job? = null
    private var turn = 0L
    private var issued = 0L
    private var shown = 0L

    /** A turn was confirmed at sample position [position]: the first partial is due one interval on. */
    fun turnStarted(position: Long) {
        if (!settings.enabled) return
        turnEnded()
        nextTick = position + interval
    }

    /**
     * The confirmed turn has audio up to [position]; [snapshot] copies its last `window` samples
     * (called only when a request is sent).
     */
    fun heard(position: Long, snapshot: (Long) -> ShortArray) {
        val due = nextTick ?: return
        if (position < due) return
        nextTick = position + interval
        if (job?.isActive == true) return
        val samples = snapshot(window)
        val ticket = synchronized(lock) { Ticket(turn, ++issued) }
        job = out.launch { request(samples, ticket) }
    }

    /** The turn ended or was dropped: no more partials for it, and the one in flight is cancelled. */
    fun turnEnded() {
        nextTick = null
        synchronized(lock) { turn++ }
        job?.cancel()
        job = null
    }

    private class Ticket(val turn: Long, val sequence: Long)

    private suspend fun request(samples: ShortArray, ticket: Ticket) {
        val clip = AudioClip(WavCodec.encode(PcmAudio(samples, PcmFormat(format.sampleRateHz, format.channels))), AudioEncoding.WAV)
        samples.fill(0)
        val outcome = runCatching { transcriber.transcribe(clip) }
        clip.bytes.fill(0)
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        val text = outcome.getOrNull()?.text?.value?.trim().orEmpty()
        if (text.isEmpty() || !claim(ticket)) return
        out.send(VoiceEarEvent.PartialTranscript(VoiceText(text)))
    }

    /** True, marking [ticket] shown, when its turn is current and nothing newer was shown. */
    private fun claim(ticket: Ticket): Boolean = synchronized(lock) {
        val fresh = ticket.turn == turn && ticket.sequence > shown
        if (fresh) shown = ticket.sequence
        fresh
    }

    private fun samplesOf(duration: Duration): Long = (duration.toDouble(DurationUnit.SECONDS) * format.sampleRateHz).toLong()
}
