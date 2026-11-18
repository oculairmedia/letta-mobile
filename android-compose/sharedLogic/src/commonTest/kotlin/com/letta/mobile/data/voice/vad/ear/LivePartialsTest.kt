package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Live partial transcripts from the server ear: cadence, single flight, order, cancellation, the final transcript. */
class LivePartialsTest {
    private val format = AudioFrameFormat()
    private val settings = LivePartials(enabled = true, interval = 600.milliseconds, window = 1.seconds)

    /** Answers each request through [answer], recording each clip's length in samples. */
    private class FakeTranscriber(private val answer: suspend (call: Int, samples: Int) -> String) : SpeechTranscriber {
        val lengths = mutableListOf<Int>()

        override suspend fun transcribe(audio: AudioClip): SpeechTranscript {
            val samples = (WavCodec.parse(audio.bytes) as WavParse.Decoded).audio.samples.size
            lengths += samples
            return SpeechTranscript(VoiceText(answer(lengths.size, samples)))
        }
    }

    /** Drives one [PartialTranscripts] through [script] and returns the partial texts it reported. */
    private suspend fun partials(transcriber: SpeechTranscriber, script: suspend Driver.() -> Unit): List<String> =
        channelFlow { Driver(PartialTranscripts(settings, format, transcriber, this)).script() }
            .filterIsInstance<VoiceEarEvent.PartialTranscript>()
            .map { it.text.value }
            .toList()

    private class Driver(val partials: PartialTranscripts) {
        /** The turn is heard from [from] to [to], one 512-sample frame at a time, letting requests run in between. */
        suspend fun hear(from: Long, to: Long) {
            var position = from
            while (position <= to) {
                partials.heard(position, voiced = position) { max -> ShortArray(minOf(position, max).toInt()) { 1 } }
                yield()
                position += FRAME
            }
        }
    }

    @Test
    fun aPartialIsRequestedEveryIntervalOfAudioWithAtMostTheWindow() = runTest {
        val transcriber = FakeTranscriber { call, _ -> "words $call" }
        val shown = partials(transcriber) {
            partials.turnStarted(0)
            hear(FRAME, 95 * FRAME)
            partials.turnEnded()
        }
        assertEquals(listOf(9_728, 16_000, 16_000, 16_000, 16_000), transcriber.lengths, "every 600 ms of audio, capped at the 1 s window")
        assertEquals(List(5) { "words ${it + 1}" }, shown)
    }

    @Test
    fun aTickWhileARequestIsInFlightIsSkipped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber { call, _ ->
            if (call == 1) gate.await()
            "words $call"
        }
        val shown = partials(transcriber) {
            partials.turnStarted(0)
            hear(FRAME, 60 * FRAME)
            assertEquals(1, transcriber.lengths.size, "three ticks passed, one request in flight")
            gate.complete(Unit)
            yield()
            hear(61 * FRAME, 80 * FRAME)
            partials.turnEnded()
        }
        assertEquals(2, transcriber.lengths.size)
        assertEquals(listOf("words 1", "words 2"), shown)
    }

    @Test
    fun aResponseForAnEarlierTurnIsDropped() = runTest {
        val late = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber { call, _ ->
            // The first request ignores its cancellation and answers after the next turn started.
            if (call == 1) withContext(NonCancellable) { late.await() }
            "words $call"
        }
        val shown = partials(transcriber) {
            partials.turnStarted(0)
            hear(FRAME, 20 * FRAME)
            partials.turnEnded()
            partials.turnStarted(40 * FRAME)
            late.complete(Unit)
            yield()
            hear(41 * FRAME, 60 * FRAME)
            partials.turnEnded()
        }
        assertEquals(listOf("words 2"), shown, "the stale answer never shows")
    }

    @Test
    fun endingTheTurnCancelsTheRequestInFlight() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber { _, _ ->
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
            "never"
        }
        val shown = partials(transcriber) {
            partials.turnStarted(0)
            hear(FRAME, 20 * FRAME)
            partials.turnEnded()
            yield()
            assertTrue(cancelled.isCompleted, "the partial in flight was cancelled with its turn")
            hear(21 * FRAME, 60 * FRAME)
        }
        assertEquals(emptyList(), shown)
        assertEquals(1, transcriber.lengths.size, "nothing is requested after the turn ended")
    }

    @Test
    fun anEmptyOrFailedPartialShowsNothingAndDoesNotStopTheNext() = runTest {
        val transcriber = FakeTranscriber { call, _ ->
            when (call) {
                1 -> error("unreachable")
                2 -> "  "
                else -> "words $call"
            }
        }
        val shown = partials(transcriber) {
            partials.turnStarted(0)
            hear(FRAME, 60 * FRAME)
            partials.turnEnded()
        }
        assertEquals(listOf("words 3"), shown)
    }

    @Test
    fun theWholeTurnStaysTheUtteranceAndPartialsComeBeforeItsEnd() = runTest {
        val finalLength = finalClipLength(SCRIPT)
        val transcriber = FakeTranscriber { _, samples -> if (samples == finalLength) "the whole turn" else "so far" }
        val events = ear(SCRIPT, transcriber, settings).listen().untilEnd()
        val partialsSeen = events.filterIsInstance<VoiceEarEvent.PartialTranscript>()
        assertTrue(partialsSeen.isNotEmpty(), "partials while the person speaks")
        assertTrue(partialsSeen.all { it.text.value == "so far" })
        assertTrue(events.indexOf(VoiceEarEvent.SpeechEnded) > events.indexOfLast { it is VoiceEarEvent.PartialTranscript })
        assertEquals(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("the whole turn"))), events.last(), "the utterance is the whole turn")
        assertEquals(finalLength, transcriber.lengths.last())
    }

    @Test
    fun withPartialsOffOnlyTheWholeTurnIsTranscribed() = runTest {
        val transcriber = FakeTranscriber { _, _ -> "the whole turn" }
        val events = ear(SCRIPT, transcriber, LivePartials()).listen().untilEnd()
        assertTrue(events.none { it is VoiceEarEvent.PartialTranscript })
        assertEquals(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("the whole turn"))), events.last())
        assertEquals(1, transcriber.lengths.size)
    }

    @Test
    fun noPartialIsRequestedOnceThePersonHasBeenQuietForTheCutoff() = runTest {
        val transcriber = FakeTranscriber { call, _ -> "words $call" }
        partials(transcriber) {
            partials.turnStarted(0)
            // Voice stops at 18 frames; the first tick (19 frames) is 32 ms into silence, the next ones far past 300 ms.
            var position = FRAME
            while (position <= 80 * FRAME) {
                partials.heard(position, voiced = minOf(position, 18 * FRAME)) { max -> ShortArray(minOf(position, max).toInt()) }
                yield()
                position += FRAME
            }
            partials.turnEnded()
        }
        assertEquals(1, transcriber.lengths.size, "only the tick inside the cutoff sends")
    }

    @Test
    fun theFinalRequestNeverWaitsBehindAPartialOnASingleWorkerServer() = runTest {
        val server = SingleWorkerServer(this)
        pacedEar(server, settings.copy(interval = 600.milliseconds)).listen().untilEnd()
        val final = server.requests.last()
        assertTrue(server.requests.size > 1, "partials were requested")
        assertEquals(final.arrivedMs, final.startedMs, "the server was idle when the final request arrived")
    }

    @Test
    fun withoutTheCutoffTheFinalWouldWaitBehindAPartial() = runTest {
        val server = SingleWorkerServer(this)
        pacedEar(server, settings.copy(interval = 600.milliseconds, quietCutoff = 10.seconds)).listen().untilEnd()
        val final = server.requests.last()
        assertTrue(final.startedMs > final.arrivedMs, "the rig detects a final queued behind a partial")
    }

    /**
     * A transcription server with one worker taking [WORK_MS] per request, in arrival order. A
     * request the client cancels still occupies the worker, as on a real server.
     */
    private class SingleWorkerServer(private val scope: TestScope) : SpeechTranscriber {
        class Request(val arrivedMs: Long, val startedMs: Long)

        val requests = mutableListOf<Request>()
        private var freeAtMs = 0L

        override suspend fun transcribe(audio: AudioClip): SpeechTranscript {
            val now = scope.testScheduler.currentTime
            val start = maxOf(now, freeAtMs)
            freeAtMs = start + WORK_MS
            requests += Request(now, start)
            delay(freeAtMs - now)
            return SpeechTranscript(VoiceText("words"))
        }
    }

    /** The ear over [SCRIPT] delivered in real time (32 ms per frame, on the test clock). */
    private fun pacedEar(transcriber: SpeechTranscriber, partials: LivePartials): VadVoiceEar {
        val source = object : AudioFrameSource {
            override val format = AudioFrameFormat()

            override fun frames() = flow {
                var position = 0L
                SCRIPT.forEach { samples ->
                    delay(FRAME_MS)
                    emit(AudioFrame(samples.copyOf(), SamplePosition(position)))
                    position += samples.size
                }
                awaitCancellation()
            }
        }
        val config = VadEarConfig(policy = EndOfTurnPolicy(), detectors = SpeechDetectorFactory { ValueDetector() }, partials = partials)
        return VadVoiceEar(source, transcriber, config)
    }

    @Test
    fun settingsRejectEmptyDurations() {
        assertFailsWith<IllegalArgumentException> { LivePartials(interval = 0.milliseconds) }
        assertFailsWith<IllegalArgumentException> { LivePartials(window = 0.milliseconds) }
    }

    private fun ear(script: List<ShortArray>, transcriber: SpeechTranscriber, partials: LivePartials): VadVoiceEar {
        val config = VadEarConfig(policy = EndOfTurnPolicy(), detectors = SpeechDetectorFactory { ValueDetector() }, partials = partials)
        return VadVoiceEar(ScriptedSource(script.map { it.copyOf() }), transcriber, config)
    }

    /** The whole-turn clip length for [script], measured with partials off. */
    private suspend fun finalClipLength(script: List<ShortArray>): Int {
        val measure = FakeTranscriber { _, _ -> "measured" }
        ear(script, measure, LivePartials()).listen().untilEnd()
        return measure.lengths.single()
    }

    /** Every event up to and including the first end of turn. */
    private suspend fun Flow<VoiceEarEvent>.untilEnd(): List<VoiceEarEvent> {
        val seen = mutableListOf<VoiceEarEvent>()
        takeWhile { event ->
            seen += event
            event !is VoiceEarEvent.EndOfTurn
        }.toList()
        return seen
    }

    private companion object {
        const val FRAME = 512L
        const val FRAME_MS = 32L
        const val WORK_MS = 450L

        /** Pre-roll, about two seconds of speech, then the silence that ends the turn. */
        val SCRIPT = frames(PRE_ROLL_VALUE, 4) + frames(SPEECH_VALUE, 60) + frames(0, 32)
    }
}
