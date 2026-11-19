package com.letta.mobile.data.voice.session.telemetry

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long each stage of one turn took, reported once per answered turn when its answer's first
 * audio plays, through [report]:
 *
 * - `decisionMs`: from the estimated end of the person's speech to the ear's end-of-turn decision
 *   (the trailing silence it waited out); missing for an ear that decides with its transcript.
 * - `sttMs`: from that decision to the accepted final transcript; for an ear that decides with its
 *   transcript, from the end of speech to its final result.
 * - `agentFirstSentenceMs`: from sending the utterance to the first sentence handed to speech.
 * - `ttsFirstAudioMs`: from that sentence to the first audio playing.
 * - `e2eMs`: from the estimated end of speech to the first audio playing.
 *
 * A stage that was not seen is missing. Only the latest turn is followed: a turn that closes before
 * the previous one was answered replaces it, and a dropped turn or a stop forgets it. Durations
 * only: never audio, words, a key or an address.
 */
internal class TurnTimings(
    private val timeSource: TimeSource,
    private val report: (List<VoiceTelemetryField>) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var turn: Turn? = null

    /** The server ear closed a turn after [trailingSilence] without voice: the person stopped speaking that long ago. */
    fun closed(trailingSilence: Duration) = synchronized(lock) {
        val now = timeSource.markNow()
        turn = Turn(speechEnd = now - trailingSilence, decided = now, decisionMs = trailingSilence.inWholeMilliseconds)
    }

    /** An ear that decides with its transcript produced its final result [sinceEndOfSpeech] after the speech ended. */
    fun heardLocally(sinceEndOfSpeech: Duration?) = synchronized(lock) {
        val since = sinceEndOfSpeech ?: Duration.ZERO
        turn = Turn(speechEnd = timeSource.markNow() - since, sttMs = sinceEndOfSpeech?.inWholeMilliseconds)
    }

    /** The closed turn's final transcript was accepted. */
    fun transcribed() = update { current ->
        val decided = current.decided ?: return@update current
        if (current.sttMs != null) current else current.copy(sttMs = decided.elapsedNow().inWholeMilliseconds)
    }

    /** The turn's utterance is being sent to the agent. */
    fun sent() = update { current -> if (current.sent != null) current else current.copy(sent = timeSource.markNow()) }

    /** A sentence of the answer was handed to speech; only the first after the send counts. */
    fun sentenceRequested() = update { current ->
        if (current.sent == null || current.firstSentence != null) current else current.copy(firstSentence = timeSource.markNow())
    }

    /** The answer's first audio started playing: the turn's timing is reported and forgotten. */
    fun audioStarted() {
        val finished = synchronized(lock) { turn.also { turn = null } } ?: return
        val now = timeSource.markNow()
        val sentence = finished.firstSentence
        val agent = sentence?.let { first -> finished.sent?.let { (first - it).inWholeMilliseconds } }
        report(
            listOf(
                "decisionMs" attr finished.decisionMs,
                "sttMs" attr finished.sttMs,
                "agentFirstSentenceMs" attr agent,
                "ttsFirstAudioMs" attr sentence?.let { (now - it).inWholeMilliseconds },
                "e2eMs" attr (now - finished.speechEnd).inWholeMilliseconds,
            ),
        )
    }

    /** The turn was dropped (its transcript was not the person's words) or voice mode stopped: nothing is reported for it. */
    fun forget() = synchronized(lock) { turn = null }

    private inline fun update(change: (Turn) -> Turn) = synchronized(lock) {
        turn = turn?.let(change)
    }

    /** What is known so far about the turn being followed. */
    private data class Turn(
        val speechEnd: TimeMark,
        val decided: TimeMark? = null,
        val decisionMs: Long? = null,
        val sttMs: Long? = null,
        val sent: TimeMark? = null,
        val firstSentence: TimeMark? = null,
    )
}

/** The time from [earlier] to this mark. */
private operator fun TimeMark.minus(earlier: TimeMark): Duration = earlier.elapsedNow() - elapsedNow()
