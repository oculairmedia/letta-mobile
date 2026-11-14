package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EndpointEnginesTest {
    private val base = SpeechEndpointUrl("https://tts.example.net")
    private val origin = EndpointOrigin.of(base)!!
    private val settings = VoiceEndpointSettings(baseUrl = base)
    private var requests = 0
    private var status = HttpStatusCode.OK
    private var failingRequest = 0

    private fun client(): SpeechEndpointClient = SpeechEndpointClient(
        MockEngine {
            val number = ++requests
            val code = if (number == failingRequest) HttpStatusCode.Unauthorized else status
            if (it.url.encodedPath.endsWith("/speech")) {
                respond(SPEECH_WAV, code)
            } else {
                respond("""{"text":"hello"}""", code, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
        SpeechEndpointConfig(base),
    )

    private fun grants(vararg granted: VoiceEndpointGrant) = VoiceEndpointGrants(granted.toSet())

    private val clip = AudioClip(byteArrayOf(9), AudioEncoding.WAV)

    @Test
    fun originIsSchemeHostAndPortOnly() {
        assertEquals("https://tts.example.net:443", origin.value)
        assertEquals(EndpointOrigin("http://h:8880"), EndpointOrigin.of(SpeechEndpointUrl("http://H:8880/v1?x=1")))
        assertEquals(null, EndpointOrigin.of(SpeechEndpointUrl("ftp://h")))
    }

    @Test
    fun noRequestIsMadeWithoutAGrantForThatEndpointAndPayload() = runTest {
        val wrongPayload = grants(VoiceEndpointGrant(origin, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        val other = EndpointOrigin("https://other.net:443")
        val wrongHost = grants(VoiceEndpointGrant(other, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION))
        listOf(VoiceEndpointGrants(), wrongPayload, wrongHost).forEach { recorded ->
            val failure = assertFailsWith<SpeechEndpointException> {
                EndpointTranscriber(client(), { recorded }, settings).transcribe(clip)
            }
            assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, failure.code)
        }
        val noSynthesis = assertFailsWith<SpeechEndpointException> {
            EndpointSpeechSynthesizer(client(), { wrongHost }, settings).synthesize(VoiceText("hi"))
        }
        assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, noSynthesis.code)
        assertEquals(0, requests)
    }

    @Test
    fun grantedTranscriptionIsSent() = runTest {
        val granted = grants(VoiceEndpointGrant(origin, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION))
        val text = withContext(Dispatchers.Default) { EndpointTranscriber(client(), { granted }, settings).transcribe(clip) }
        assertEquals("hello", text.value)
        assertEquals(1, requests)
    }

    @Test
    fun endpointMouthSpeaksInOrderAndReportsLevels() = runTest {
        val player = RecordingPlayer()
        val mouth = mouth(player)
        val events = mutableListOf<VoiceMouthEvent>()
        val job = backgroundScope.launch { mouth.events.filterNot { it is VoiceMouthEvent.OutputLevel }.take(4).toList(events) }
        testScheduler.runCurrent()
        mouth.speak(SpeechSegment(SpeechSegmentId(1), VoiceText("One.")))
        mouth.speak(SpeechSegment(SpeechSegmentId(2), VoiceText("Two.")))
        job.join()
        val expected = listOf(
            VoiceMouthEvent.Started(SpeechSegmentId(1)),
            VoiceMouthEvent.Finished(SpeechSegmentId(1)),
            VoiceMouthEvent.Started(SpeechSegmentId(2)),
            VoiceMouthEvent.Finished(SpeechSegmentId(2)),
        )
        assertEquals(expected, events)
        assertEquals(2, player.played)
        assertEquals(listOf(SPEECH_SAMPLES, SPEECH_SAMPLES), player.samplesPerSegment, "every segment's audio reached the player")
    }

    @Test
    fun endpointMouthReportsSynthesisFailureWithTheEndpointCode() = runTest {
        status = HttpStatusCode.Unauthorized
        val mouth = mouth(RecordingPlayer())
        val first = async { mouth.events.first { it is VoiceMouthEvent.Failed } }
        testScheduler.runCurrent()
        mouth.speak(SpeechSegment(SpeechSegmentId(1), VoiceText("One.")))
        val event = first.await() as VoiceMouthEvent.Failed
        assertEquals(VoiceErrorCode.UNAUTHORIZED, event.error.code)
        mouth.stop()
        assertTrue(requests >= 1)
    }

    private fun TestScope.mouth(player: AudioPlayer): EndpointVoiceMouth =
        EndpointVoiceMouth(synthesizer(), player, backgroundScope + Dispatchers.Default)

    private fun synthesizer(): EndpointSpeechSynthesizer {
        val granted = grants(VoiceEndpointGrant(origin, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        return EndpointSpeechSynthesizer(client(), { granted }, settings)
    }

    @Test
    fun stoppingTheMouthStartsNoWorkersAndLeavesNoneBehind() = runTest {
        val owner = Job(backgroundScope.coroutineContext[Job])
        val mouth = EndpointVoiceMouth(synthesizer(), GatedPlayer(), backgroundScope + owner)
        testScheduler.runCurrent()
        assertEquals(emptyList(), owner.children.toList(), "an unused mouth runs nothing")
        mouth.stop()
        assertEquals(emptyList(), owner.children.toList(), "stopping an unused mouth starts nothing")
        mouth.speak(SpeechSegment(SpeechSegmentId(1), VoiceText("One.")))
        assertEquals(1, owner.children.count())
        mouth.stop()
        assertEquals(emptyList(), owner.children.toList(), "a stopped mouth leaves no worker behind")
    }

    private class RecordingPlayer : AudioPlayer {
        var played = 0
        val samplesPerSegment = mutableListOf<Int>()

        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
            var samples = 0
            chunks.collect { samples += it.samples.size }
            onLevel(AudioLevel(0.5f))
            samplesPerSegment += samples
            played++
        }
    }

    @Test
    fun aSynthesisFailureStopsPlaybackOfPrefetchedSegmentsAndFurtherRequests() = runTest {
        failingRequest = 2
        val player = GatedPlayer()
        val mouth = mouth(player)
        val failed = async { mouth.events.first { it is VoiceMouthEvent.Failed } }
        val later = async { mouth.events.first { it == VoiceMouthEvent.Started(SpeechSegmentId(2)) } }
        testScheduler.runCurrent()
        (1..4).forEach { mouth.speak(SpeechSegment(SpeechSegmentId(it.toLong()), VoiceText("Segment $it."))) }
        assertEquals(SpeechSegmentId(2), (failed.await() as VoiceMouthEvent.Failed).id)
        player.gate.complete(Unit)
        val secondPlayed = withContext(Dispatchers.Default) { withTimeoutOrNull(300.milliseconds) { later.await() } }
        later.cancel()
        assertEquals(null, secondPlayed)
        assertEquals(2, requests)
    }

    /** Holds each segment until [gate] opens. */
    private class GatedPlayer : AudioPlayer {
        val gate = CompletableDeferred<Unit>()

        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
            gate.await()
        }
    }

    @Test
    fun aFailureTearsThePipelineDownEvenWhenNobodyTakesTheEvent() = runTest {
        var proven = 0
        withContext(Dispatchers.Default) {
            repeat(TEARDOWN_REPEATS) { attempt ->
                val iteration = Job(coroutineContext[Job])
                if (TeardownRig(this + iteration).prove(attempt)) proven++
                iteration.cancel()
            }
        }
        assertEquals(TEARDOWN_REPEATS, proven)
    }

    @Test
    fun grantOriginsNormaliseCaseAndNeverMatchByPrefix() {
        val local = EndpointOrigin.of(SpeechEndpointUrl("http://localhost:8880"))!!
        val granted = grants(VoiceEndpointGrant(local, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        val cases = mapOf(
            "HTTP://LOCALHOST:8880" to true,
            "http://localhost:8880/v1/" to true,
            "http://localhost:88" to false,
            "http://localhost:8880@evil.example" to false,
            "http://localhost.evil.example:8880" to false,
            "https://localhost:8880" to false,
        )
        cases.forEach { (url, allowed) ->
            assertEquals(allowed, granted.allows(SpeechEndpointUrl(url), VoiceEndpointPayload.TEXT_FOR_SYNTHESIS), url)
        }
    }

    /**
     * One deterministic teardown scenario. Request 2 (segment 2's synthesis) waits for
     * [releaseFailure] and then fails; the player holds clip 1 and, once a subscriber holds the first
     * event without consuming more, fills the event buffer. Every step waits on an explicit gate.
     */
    private inner class TeardownRig(private val scope: CoroutineScope) {
        private var requests = 0
        private val releaseFailure = CompletableDeferred<Unit>()
        private val player = HoldingPlayer(levels = FLOOD_LEVELS)
        private val granted = grants(VoiceEndpointGrant(origin, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        private val client = SpeechEndpointClient(
            MockEngine {
                val number = ++requests
                if (number == 2) releaseFailure.await()
                val code = if (number == 2) HttpStatusCode.Unauthorized else HttpStatusCode.OK
                respond(SPEECH_WAV, code)
            },
            SpeechEndpointConfig(base),
        )
        private val mouth = EndpointVoiceMouth(EndpointSpeechSynthesizer(client, { granted }, settings), player, scope)

        /** Runs the scenario once; true when every step held. */
        suspend fun prove(attempt: Int): Boolean {
            val subscribed = CompletableDeferred<Unit>()
            val holding = CompletableDeferred<Unit>()
            scope.launch {
                mouth.events.onSubscription { subscribed.complete(Unit) }.collect {
                    holding.complete(Unit)
                    awaitCancellation()
                }
            }
            subscribed.await()
            (1..4).forEach { mouth.speak(SpeechSegment(SpeechSegmentId(it.toLong()), VoiceText("Segment $it."))) }
            holding.await()
            player.startLevels.complete(Unit)
            player.levelsDone.await()
            assertTrue(mouth.eventBufferFull(), "attempt $attempt: event buffer must be full before the failure")
            releaseFailure.complete(Unit)
            assertEquals(Unit, withTimeoutOrNull(TEARDOWN_BUDGET) { player.cancelled.await() }, "attempt $attempt: playback cancelled")
            assertEquals(2, requests, "attempt $attempt: no synthesis after the failure")
            return true
        }
    }

    /** Holds every segment until cancelled; reports [levels] output levels once [startLevels] opens. */
    private class HoldingPlayer(private val levels: Int) : AudioPlayer {
        val startLevels = CompletableDeferred<Unit>()
        val levelsDone = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
            startLevels.await()
            repeat(levels) { onLevel(AudioLevel(0.5f)) }
            levelsDone.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }

    private companion object {
        const val TEARDOWN_REPEATS = 10
        const val FLOOD_LEVELS = 200
        val TEARDOWN_BUDGET = 5.seconds
        const val SPEECH_SAMPLES = 480
        val SPEECH_WAV = WavCodec.encode(PcmAudio(ShortArray(SPEECH_SAMPLES) { 1_000 }, PcmFormat(24_000)))
    }
}
