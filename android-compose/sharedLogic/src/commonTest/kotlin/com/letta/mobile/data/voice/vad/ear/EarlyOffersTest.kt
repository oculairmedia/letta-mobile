package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.audio.pcm.WavParse
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.SpeechDetectorFactory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The server ear offering a turn's words before its end: after a pause (its transcription reused
 * for the turn), or from live partial words that already cover a closed turn; voice after an offer
 * withdraws it, and the turn's end always carries the authoritative words.
 */
class EarlyOffersTest {
    /** Answers through [answer] with each clip's length in samples, recording the lengths. */
    private class Transcriber(private val answer: (call: Int, samples: Int) -> String) : SpeechTranscriber {
        val lengths = mutableListOf<Int>()

        override suspend fun transcribe(audio: AudioClip): SpeechTranscript {
            val samples = (WavCodec.parse(audio.bytes) as WavParse.Decoded).audio.samples.size
            lengths += samples
            return SpeechTranscript(VoiceText(answer(lengths.size, samples)))
        }
    }

    private val tentative = { event: VoiceEarEvent -> event is VoiceEarEvent.TentativeEndOfTurn }

    private fun List<VoiceEarEvent>.offered(): List<String> =
        filterIsInstance<VoiceEarEvent.TentativeEndOfTurn>().map { it.utterance.text.value }

    private fun List<VoiceEarEvent>.final(): String = (last() as VoiceEarEvent.EndOfTurn).utterance.text.value

    @Test
    fun aPauseOffersTheWordsAndTheirTranscriptionBecomesTheTurns() = runTest {
        val transcriber = Transcriber { _, _ -> "book a table" }
        val events = listen(speech(30) + silence(40), transcriber, EarlyTurns(pause = 350.milliseconds))
        assertEquals(listOf("book a table"), events.offered())
        assertTrue(events.indexOfFirst(tentative) < events.indexOf(VoiceEarEvent.SpeechEnded), "offered before the turn's end")
        assertEquals("book a table", events.final())
        assertEquals(1, transcriber.lengths.size, "the turn is not transcribed again")
    }

    @Test
    fun voiceAfterTheOfferWithdrawsItAndTheWholeTurnIsHeard() = runTest {
        val transcriber = Transcriber { call, _ -> if (call == 1) "book a table" else "book a table for two" }
        val script = speech(30) + silence(20) + speech(20) + silence(40)
        val events = listen(script, transcriber, EarlyTurns(pause = 350.milliseconds))
        val offeredAt = events.indexOfFirst(tentative)
        val withdrawnAt = events.indexOf(VoiceEarEvent.TentativeWithdrawn)
        assertTrue(offeredAt in 0 until withdrawnAt, "the first offer is withdrawn when the person goes on")
        assertTrue(withdrawnAt < events.indexOf(VoiceEarEvent.SpeechEnded), "one turn, still open")
        assertEquals("book a table", events.offered().first())
        assertEquals("book a table for two", events.final())
        assertEquals(2, transcriber.lengths.size)
        assertTrue(transcriber.lengths[1] > transcriber.lengths[0], "the second transcription hears the whole turn")
    }

    @Test
    fun partialWordsThatCoverTheClosedTurnAreOfferedAndTheFinalTranscriptStillFollows() = runTest {
        // Only the whole turn holds the end-of-turn silence: partial clips stop well short of it.
        val transcriber = Transcriber { _, samples -> if (samples > WHOLE_TURN_AT_LEAST) "Hello there." else "hello there" }
        val partials = LivePartials(enabled = true, interval = 600.milliseconds, settle = 160.milliseconds)
        val events = listen(speech(30) + silence(40), transcriber, EarlyTurns(), partials)
        val endedAt = events.indexOf(VoiceEarEvent.SpeechEnded)
        assertEquals(endedAt + 1, events.indexOfFirst(tentative), "offered as soon as the turn closes")
        assertEquals(listOf("hello there"), events.offered())
        assertEquals("Hello there.", events.final(), "the final transcription still runs and its words are the turn's")
    }

    @Test
    fun partialWordsThatStopShortOfTheLastVoiceAreNotOffered() = runTest {
        val transcriber = Transcriber { _, _ -> "hello" }
        val partials = LivePartials(enabled = true, interval = 600.milliseconds)
        val events = listen(speech(30) + silence(40), transcriber, EarlyTurns(), partials)
        assertTrue(events.any { it is VoiceEarEvent.PartialTranscript }, "partials were shown")
        assertEquals(emptyList(), events.offered())
    }

    @Test
    fun aPauseAsLongAsTheEndOfTurnSilenceNeverOffers() = runTest {
        val transcriber = Transcriber { _, _ -> "hello" }
        val events = listen(speech(30) + silence(40), transcriber, EarlyTurns(pause = EndOfTurnPolicy.DEFAULT_TRAILING_SILENCE))
        assertEquals(emptyList(), events.offered())
        assertEquals(1, transcriber.lengths.size)
    }

    @Test
    fun aFailedEarlyTranscriptionIsTranscribedAgainAtTheTurnsEnd() = runTest {
        var calls = 0
        val transcriber = object : SpeechTranscriber {
            override suspend fun transcribe(audio: AudioClip): SpeechTranscript {
                calls++
                if (calls == 1) error("server busy")
                return SpeechTranscript(VoiceText("hello"))
            }
        }
        val events = listen(speech(30) + silence(40), transcriber, EarlyTurns(pause = 350.milliseconds))
        assertEquals(emptyList(), events.offered())
        assertEquals("hello", events.final())
        assertEquals(2, calls)
    }

    @Test
    fun settingsRejectAnEmptyPause() {
        assertFailsWith<IllegalArgumentException> { EarlyTurns(pause = 0.milliseconds) }
        assertFailsWith<IllegalArgumentException> { LivePartials(settle = (-1).milliseconds) }
    }

    /** Every event of one listen over [script], delivered in real time, up to and including the first end of turn. */
    private suspend fun listen(
        script: List<ShortArray>,
        transcriber: SpeechTranscriber,
        early: EarlyTurns,
        partials: LivePartials = LivePartials(),
    ): List<VoiceEarEvent> {
        val config = VadEarConfig(
            policy = EndOfTurnPolicy(),
            detectors = SpeechDetectorFactory { ValueDetector() },
            partials = partials,
            early = early,
        )
        val seen = mutableListOf<VoiceEarEvent>()
        VadVoiceEar(PacedSource(script), transcriber, config).listen().takeWhile { event ->
            seen += event
            event !is VoiceEarEvent.EndOfTurn
        }.toList()
        return seen
    }

    private fun speech(frames: Int) = frames(SPEECH_VALUE, frames)

    private fun silence(frames: Int) = frames(0, frames)

    /** [script] at the pace of a microphone: one 32 ms frame at a time on the test clock. */
    private class PacedSource(private val script: List<ShortArray>) : AudioFrameSource {
        override val format = AudioFrameFormat()

        override fun frames(): Flow<AudioFrame> = flow {
            var position = 0L
            (frames(PRE_ROLL_VALUE, 4) + script).forEach { samples ->
                delay(FRAME_MS)
                emit(AudioFrame(samples.copyOf(), SamplePosition(position)))
                position += samples.size
            }
            awaitCancellation()
        }
    }

    private companion object {
        const val FRAME_MS = 32L

        /** Pre-roll and 30 frames of speech (17 920 samples) plus half a second of silence. */
        const val WHOLE_TURN_AT_LEAST = 25_920
    }
}
