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
    /**
     * No new partial is requested once the person has been silent this long: the turn is probably
     * ending, and the transcription server should be idle when its final request arrives (a single
     * worker serves requests in order, and cancelling a request does not stop the server's work).
     */
    val quietCutoff: Duration = 300.milliseconds,
    /**
     * Once the person has been silent this long (and still within [quietCutoff]), one more partial
     * is requested at once, off the interval, if none sent so far reaches their last voice: its
     * words then cover the whole turn and can be offered as its transcript. Null never does.
     */
    val settle: Duration? = null,
) {
    init {
        require(interval.isPositive()) { "Partial interval must be positive" }
        require(window.isPositive()) { "Partial window must be positive" }
        require(!quietCutoff.isNegative()) { "Partial quiet cutoff must not be negative" }
        require(settle == null || !settle.isNegative()) { "Partial settle must not be negative" }
    }

    /** Defaults. */
    companion object {
        /**
         * The settle used when a covering partial is wanted (so its words can be offered as the
         * turn's): well inside [quietCutoff]'s default, after a typical gap between words.
         */
        val DEFAULT_SETTLE: Duration = 160.milliseconds
    }
}

/**
 * Drives [LivePartials] for one listen. All timing is audio time (sample positions). At most one
 * partial request is in flight: a tick that comes while one runs is skipped. A response is shown
 * only if its turn is still the current one and no newer response was shown; ending or dropping
 * the turn cancels the request in flight. Partials never become the turn's utterance: the
 * whole-turn transcription at its end stays authoritative: [covering] only offers the newest
 * partial's words when they already reach the person's last voice. A failed partial is ignored
 * silently. Partial audio is wiped once sent; nothing about a partial is logged.
 */
internal class PartialTranscripts(
    private val settings: LivePartials,
    private val format: AudioFrameFormat,
    private val transcriber: SpeechTranscriber,
    private val out: ProducerScope<VoiceEarEvent>,
) {
    private val interval = samplesOf(settings.interval)
    private val window = samplesOf(settings.window)
    private val quietCutoff = samplesOf(settings.quietCutoff)
    private val settle = settings.settle?.let(::samplesOf)
    private var lastVoiced = 0L
    private var voicedSoFar = 0L
    private val lock = SynchronizedObject()
    private var nextTick: Long? = null
    private var job: Job? = null
    private var turn = 0L
    private var issued = 0L
    private var shown = 0L
    private var issuedEnd = 0L
    private var shownCover: Cover? = null

    /** A turn was confirmed at sample position [position]: the first partial is due one interval on. */
    fun turnStarted(position: Long) {
        if (!settings.enabled) return
        turnEnded()
        nextTick = position + interval
        lastVoiced = position
        voicedSoFar = 0
    }

    /**
     * The confirmed turn has audio up to [position], [voiced] samples of it judged voice so far;
     * [snapshot] copies its last `window` samples (called only when a request is sent). A tick that
     * comes after the quiet cutoff of silence sends nothing.
     */
    fun heard(position: Long, voiced: Long, snapshot: (Long) -> ShortArray) {
        if (voiced != voicedSoFar) {
            voicedSoFar = voiced
            lastVoiced = position
        }
        val due = nextTick ?: return
        val quiet = position - lastVoiced
        if (quiet >= quietCutoff) return
        if (position < due && !settles(quiet)) return
        nextTick = position + interval
        if (job?.isActive == true) return
        val samples = snapshot(window)
        issuedEnd = position
        val ticket = synchronized(lock) { Ticket(turn, ++issued, position, whole = samples.size < window) }
        job = out.launch { request(samples, ticket) }
    }

    /** Whether, [quiet] samples into a pause, one more partial should go now: none sent so far reaches the last voice. */
    private fun settles(quiet: Long): Boolean {
        val after = settle ?: return false
        return quiet >= after && issuedEnd < lastVoiced
    }

    /**
     * The newest partial's words, when they cover the whole turn so far: its audio started with the
     * turn (not cut to the window) and reached [lastVoice], the end of the person's last voice.
     * Null otherwise. Call before [turnEnded].
     */
    fun covering(lastVoice: Long): VoiceText? = synchronized(lock) {
        shownCover?.takeIf { it.turn == turn && it.whole && it.end >= lastVoice }?.text
    }

    /** The turn ended or was dropped: no more partials for it, and the one in flight is cancelled. */
    fun turnEnded() {
        nextTick = null
        issuedEnd = 0
        synchronized(lock) {
            turn++
            shownCover = null
        }
        job?.cancel()
        job = null
    }

    /** One partial request: its turn, its order, where its audio ends and whether it holds the whole turn. */
    private class Ticket(val turn: Long, val sequence: Long, val end: Long = 0, val whole: Boolean = false)

    /** The words a shown partial heard, and the audio they cover. */
    private class Cover(val turn: Long, val end: Long, val whole: Boolean, val text: VoiceText)

    private suspend fun request(samples: ShortArray, ticket: Ticket) {
        val clip = AudioClip(WavCodec.encode(PcmAudio(samples, PcmFormat(format.sampleRateHz, format.channels))), AudioEncoding.WAV)
        samples.fill(0)
        val outcome = runCatching { transcriber.transcribe(clip) }
        clip.bytes.fill(0)
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        val text = outcome.getOrNull()?.text?.value?.trim().orEmpty()
        if (text.isEmpty() || !claim(ticket, VoiceText(text))) return
        out.send(VoiceEarEvent.PartialTranscript(VoiceText(text)))
    }

    /** True, marking [ticket] shown with [text], when its turn is current and nothing newer was shown. */
    private fun claim(ticket: Ticket, text: VoiceText): Boolean = synchronized(lock) {
        val fresh = ticket.turn == turn && ticket.sequence > shown
        if (fresh) {
            shown = ticket.sequence
            shownCover = Cover(ticket.turn, ticket.end, ticket.whole, text)
        }
        fresh
    }

    private fun samplesOf(duration: Duration): Long = (duration.toDouble(DurationUnit.SECONDS) * format.sampleRateHz).toLong()
}
