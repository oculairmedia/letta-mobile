package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * When the server ear offers a turn's words before it has decided the turn is over
 * ([VoiceEarEvent.TentativeEndOfTurn]). The loop answers an offer at once but plays nothing of the
 * answer until the turn's end confirms the same words, so an offer saves time without ever speaking
 * an answer to words the person did not finish.
 */
data class EarlyTurns(
    /**
     * After this much silence in a confirmed turn, the turn's audio so far is transcribed at once
     * and its words offered (the live partial words instead, when they already cover it); the
     * transcription is reused as the turn's own when the turn then ends without more voice. Voice
     * before the turn's end withdraws the offer ([VoiceEarEvent.TentativeWithdrawn]). Null never
     * offers on a pause; a pause no shorter than the policy's trailing silence never comes.
     */
    val pause: Duration? = null,
    /**
     * When the turn closes without an offer, offer the newest live partial words if they already
     * cover the whole turn: the agent starts on them while the final transcription only confirms.
     */
    val reusePartials: Boolean = true,
) {
    init {
        require(pause == null || pause.isPositive()) { "Early-turn pause must be positive" }
    }

    /** Defaults. */
    companion object {
        /** The pause after which a turn's words are offered when early turns are on. */
        val DEFAULT_PAUSE: Duration = 350.milliseconds

        /** The shortest pause a person may choose. */
        val MIN_PAUSE: Duration = 200.milliseconds
    }
}

/** What [EarlyOffers] reads about the confirmed turn being heard. */
internal interface HeardTurn {
    /** Whether a confirmed turn is open now. */
    val confirmed: Boolean

    /** Where the person's voice last ended, as a sample position. */
    val lastVoice: Long

    /** What was measured about the turn so far. */
    fun report(): EarTurnReport

    /** A copy of the turn's audio so far, pre-roll included; the receiver wipes it. */
    fun audio(): ShortArray

    /** The newest live partial words, when they already cover the turn so far. */
    fun covering(): VoiceText?
}

/**
 * What one turn has offered early, for one listen: at most one offer at a time, made and withdrawn
 * from the listening coroutine only, so the loop always hears an offer before its withdrawal. The
 * early transcription runs beside the listen; its audio is wiped once sent, and nothing of it is
 * logged beyond the observer's reasons.
 */
internal class EarlyOffers(
    private val parts: EarParts,
    private val out: ProducerScope<VoiceEarEvent>,
    private val turn: HeardTurn,
) {
    private val early = parts.config.early
    private val format: AudioFrameFormat = parts.config.format
    private val pause = early.pause?.takeIf { it < parts.config.policy.trailingSilence }?.let(::samplesOf)
    private var offer: Offer? = null

    /** One turn's early transcription, if any, and the words offered for it, once offered. */
    private class Offer(val transcript: Deferred<Result<SpeechTranscript>>?, var offered: VoiceText? = null)

    /**
     * A hop ended at [position]; nothing happens outside a confirmed turn. Voice in it withdraws an
     * offer; a pause long enough makes one; an early transcription that has finished is offered.
     */
    suspend fun heard(position: Long) {
        if (!turn.confirmed) return
        if (turn.lastVoice == position) return withdraw()
        val current = offer
        if (current == null) {
            if (pause != null && position - turn.lastVoice >= pause) start(turn.report(), turn.audio(), turn.covering())
            return
        }
        if (current.offered == null) completedWords(current, turn.report())?.let { offerWords(current, it, fromPartials = false) }
    }

    /**
     * The turn closed (and is not dropped): the early transcription, if one was made, for the
     * worker to use instead of transcribing again. With none, [covering] partial words are offered
     * when early turns reuse them.
     */
    suspend fun closed(report: EarTurnReport, covering: VoiceText?): Deferred<Result<SpeechTranscript>>? {
        val current = offer
        offer = null
        if (current != null) return current.transcript
        reusable(covering, report)?.let { words ->
            parts.config.observer.turnOffered(fromPartials = true)
            out.send(VoiceEarEvent.TentativeEndOfTurn(VoiceUtterance(words)))
        }
        return null
    }

    /** The turn was dropped: its early transcription is cancelled. The turn's empty end voids any offer. */
    fun discard() {
        offer?.transcript?.cancel()
        offer = null
    }

    /** The person went on speaking: the offer, if any, is withdrawn and its transcription cancelled. */
    private suspend fun withdraw() {
        val current = offer ?: return
        offer = null
        current.transcript?.cancel()
        if (current.offered != null) {
            parts.config.observer.offerWithdrawn()
            out.send(VoiceEarEvent.TentativeWithdrawn)
        }
    }

    private suspend fun start(report: EarTurnReport, samples: ShortArray, covering: VoiceText?) {
        val created = Offer(transcribe(samples))
        offer = created
        reusable(covering, report)?.let { offerWords(created, it, fromPartials = true) }
    }

    /** [covering] partial words, when early turns reuse them and the policy keeps them. */
    private fun reusable(covering: VoiceText?, report: EarTurnReport): VoiceText? =
        covering?.takeIf { early.reusePartials && accepted(it, report) }

    private suspend fun offerWords(offer: Offer, words: VoiceText, fromPartials: Boolean) {
        offer.offered = words
        parts.config.observer.turnOffered(fromPartials)
        out.send(VoiceEarEvent.TentativeEndOfTurn(VoiceUtterance(words)))
    }

    /** The early transcription's words once it has finished and the policy keeps them; null otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun completedWords(offer: Offer, report: EarTurnReport): VoiceText? {
        val transcript = offer.transcript?.takeIf { it.isCompleted && !it.isCancelled } ?: return null
        val heard = transcript.getCompleted().getOrNull() ?: return null
        val reason = parts.config.transcripts.discardReason(heard.text, report, heard.segments)
        return heard.text.takeIf { reason == null }
    }

    private fun accepted(words: VoiceText, report: EarTurnReport): Boolean =
        parts.config.transcripts.discardReason(words, report, emptyList()) == null

    /** Transcribes [samples] beside the listen; a failure is kept in the result, never thrown into the listen. */
    private fun transcribe(samples: ShortArray): Deferred<Result<SpeechTranscript>> = out.async {
        val clip = AudioClip(WavCodec.encode(PcmAudio(samples, PcmFormat(format.sampleRateHz, format.channels))), AudioEncoding.WAV)
        samples.fill(0)
        val outcome = runCatching { parts.transcriber.transcribe(clip) }
        clip.bytes.fill(0)
        outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        outcome
    }

    private fun samplesOf(duration: Duration): Long = (duration.toDouble(DurationUnit.SECONDS) * format.sampleRateHz).toLong()
}
