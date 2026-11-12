package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioPlayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
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
            respond("""{"text":"hello"}""", code, headersOf(HttpHeaders.ContentType, "application/json"))
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

    private fun TestScope.mouth(player: AudioPlayer): EndpointVoiceMouth {
        val granted = grants(VoiceEndpointGrant(origin, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        return EndpointVoiceMouth(EndpointSpeechSynthesizer(client(), { granted }, settings), player, backgroundScope + Dispatchers.Default)
    }

    private class RecordingPlayer : AudioPlayer {
        var played = 0

        override suspend fun play(clip: AudioClip, onLevel: (AudioLevel) -> Unit) {
            onLevel(AudioLevel(0.5f))
            played++
        }
    }

    @Test
    fun aSynthesisFailureStopsPlaybackOfPrefetchedSegmentsAndFurtherRequests() = runTest {
        failingRequest = 3
        val player = GatedPlayer()
        val mouth = mouth(player)
        val failed = async { mouth.events.first { it is VoiceMouthEvent.Failed } }
        val later = async { mouth.events.first { it == VoiceMouthEvent.Started(SpeechSegmentId(2)) } }
        testScheduler.runCurrent()
        (1..4).forEach { mouth.speak(SpeechSegment(SpeechSegmentId(it.toLong()), VoiceText("Segment $it."))) }
        assertEquals(SpeechSegmentId(3), (failed.await() as VoiceMouthEvent.Failed).id)
        player.gate.complete(Unit)
        val secondPlayed = withContext(Dispatchers.Default) { withTimeoutOrNull(300.milliseconds) { later.await() } }
        later.cancel()
        assertEquals(null, secondPlayed)
        assertEquals(3, requests)
    }

    /** Holds each clip until [gate] opens; [levelsFirst] level reports are made before waiting. */
    private class GatedPlayer(private val levelsFirst: Int = 0) : AudioPlayer {
        val gate = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun play(clip: AudioClip, onLevel: (AudioLevel) -> Unit) {
            repeat(levelsFirst) { onLevel(AudioLevel(0.5f)) }
            try {
                gate.await()
            } finally {
                if (!gate.isCompleted) cancelled.complete(Unit)
            }
        }
    }

    @Test
    fun aFailureTearsThePipelineDownEvenWhenNobodyTakesTheEvent() = runTest {
        failingRequest = 2
        val player = GatedPlayer(levelsFirst = 200)
        val mouth = mouth(player)
        backgroundScope.launch { mouth.events.collect { awaitCancellation() } }
        testScheduler.runCurrent()
        (1..4).forEach { mouth.speak(SpeechSegment(SpeechSegmentId(it.toLong()), VoiceText("Segment $it."))) }
        val tornDown = withContext(Dispatchers.Default) { withTimeoutOrNull(2.seconds) { player.cancelled.await() } }
        assertEquals(Unit, tornDown)
        assertEquals(2, requests)
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
}
