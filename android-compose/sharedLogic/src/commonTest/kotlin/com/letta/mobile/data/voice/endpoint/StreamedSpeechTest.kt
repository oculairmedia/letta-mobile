package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Speech played while its response streams in, over a mock endpoint whose response bodies the test
 * writes piece by piece. Every test asserts what actually reached the player.
 */
class StreamedSpeechTest {
    private val base = SpeechEndpointUrl("https://tts.example.net")
    private val settings = VoiceEndpointSettings(baseUrl = base)
    private val granted = VoiceEndpointGrants(
        setOf(VoiceEndpointGrant(EndpointOrigin.of(base)!!, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS)),
    )

    // overflow: test-only; each request's body, in request order.
    private val bodies = Channel<ByteChannel>(Channel.UNLIMITED)
    private var requests = 0

    private val client = SpeechEndpointClient(
        MockEngine {
            requests++
            val body = ByteChannel(autoFlush = true)
            bodies.send(body)
            respond(body)
        },
        SpeechEndpointConfig(base),
    )

    private val synthesizer = EndpointSpeechSynthesizer(client, { granted }, settings)

    /** Ktor and the mouth run on real time; the mock's bodies are written by the test. */
    private fun streamTest(block: suspend CoroutineScope.(mouthScope: CoroutineScope) -> Unit): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val owner = Job(coroutineContext[Job])
            try {
                block(this + owner)
            } finally {
                owner.cancel()
            }
        }
    }

    private suspend fun nextBody(): ByteChannel = withTimeout(WAIT) { bodies.receive() }

    private fun segment(id: Long) = SpeechSegment(SpeechSegmentId(id), VoiceText("Sentence $id."))

    @Test
    fun audioReachesThePlayerBeforeTheResponseBodyCompletes() = streamTest { scope ->
        val player = StreamingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        val finished = scope.launchFirst(mouth) { it == VoiceMouthEvent.Finished(SpeechSegmentId(1)) }
        mouth.speak(segment(1))
        val body = nextBody()
        body.writeFully(streamedHeader() + bytesOf(FIRST))
        assertContentEquals(FIRST, player.nextSamples(FIRST.size), "the first audio is played while the body is still open")
        assertFalse(body.isClosedForWrite, "the response has not completed")
        assertFalse(finished.isCompleted)
        body.writeFully(bytesOf(SECOND))
        body.close()
        assertContentEquals(SECOND, player.nextSamples(SECOND.size))
        player.playedOut.complete(Unit)
        withTimeout(WAIT) { finished.join() }
        mouth.stop()
    }

    @Test
    fun aStreamedWaveWithPlaceholderSizesSplitAcrossOddRunsReachesThePlayerWhole() = streamTest { scope ->
        val player = StreamingPlayer()
        player.playedOut.complete(Unit)
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        val finished = scope.launchFirst(mouth) { it == VoiceMouthEvent.Finished(SpeechSegmentId(1)) }
        mouth.speak(segment(1))
        val whole = streamedHeader() + bytesOf(FIRST + SECOND)
        val body = nextBody()
        var at = 0
        listOf(45, 3, 1, 5, 2).forEach { size ->
            body.writeFully(whole.copyOfRange(at, at + size))
            at += size
            delay(PAUSE)
        }
        body.writeFully(whole.copyOfRange(at, whole.size))
        body.close()
        withTimeout(WAIT) { finished.join() }
        assertEquals((FIRST + SECOND).toList(), player.received.toList(), "every sample, in order, split samples rejoined")
        assertEquals(PcmFormat(RATE), player.formats.single())
        mouth.stop()
    }

    @Test
    fun interruptingMidStreamStopsThePlayerAndClosesTheRequest() = streamTest { scope ->
        val player = StreamingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        mouth.speak(segment(1))
        val body = nextBody()
        body.writeFully(streamedHeader() + bytesOf(FIRST))
        assertContentEquals(FIRST, player.nextSamples(FIRST.size))
        mouth.stop()
        assertTrue(player.cancelled.isCompleted, "playback was cut, not left to finish")
        withTimeout(WAIT) { while (!body.isClosedForWrite) delay(PAUSE) }
        assertTrue(body.isClosedForWrite, "the request in flight was closed")
        assertEquals(1, requests)
    }

    @Test
    fun prefetchedSentencesPlayInOrderOneAhead() = streamTest { scope ->
        val player = StreamingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        // overflow: test-only.
        val events = Channel<VoiceMouthEvent>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            mouth.events.onSubscription { subscribed.complete(Unit) }.collect { if (it !is VoiceMouthEvent.OutputLevel) events.send(it) }
        }
        withTimeout(WAIT) { subscribed.await() }
        (1L..3L).forEach { mouth.speak(segment(it)) }
        val first = nextBody()
        first.writeFully(streamedHeader() + bytesOf(samplesOf(1)))
        first.close()
        assertContentEquals(samplesOf(1), player.nextSamples(samplesOf(1).size))
        val second = nextBody()
        second.writeFully(streamedHeader() + bytesOf(samplesOf(2)))
        second.close()
        delay(SETTLE)
        assertEquals(2, requests, "the sentence after the next waits until the next starts playing")
        assertNull(player.chunks.tryReceive().getOrNull(), "the next sentence waits for the current one to play out")
        player.playedOut.complete(Unit)
        assertContentEquals(samplesOf(2), player.nextSamples(samplesOf(2).size))
        val third = nextBody()
        third.writeFully(streamedHeader() + bytesOf(samplesOf(3)))
        third.close()
        assertContentEquals(samplesOf(3), player.nextSamples(samplesOf(3).size))
        val expected = (1L..3L).flatMap { id ->
            listOf(VoiceMouthEvent.Started(SpeechSegmentId(id)), VoiceMouthEvent.Finished(SpeechSegmentId(id)))
        }
        val seen = withTimeout(WAIT) { List(expected.size) { events.receive() } }
        assertEquals(expected, seen)
        assertEquals(3, requests)
        mouth.stop()
    }

    @Test
    fun aSegmentFinishesOnlyOnceThePlayerHasPlayedItOut() = streamTest { scope ->
        val player = StreamingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        val finished = scope.launchFirst(mouth) { it == VoiceMouthEvent.Finished(SpeechSegmentId(1)) }
        mouth.speak(segment(1))
        val body = nextBody()
        body.writeFully(streamedHeader() + bytesOf(FIRST))
        body.close()
        assertContentEquals(FIRST, player.nextSamples(FIRST.size))
        withTimeout(WAIT) { player.streamEnded.await() }
        delay(SETTLE)
        assertFalse(finished.isCompleted, "the response ended, but the audio has not played out yet")
        player.playedOut.complete(Unit)
        withTimeout(WAIT) { finished.join() }
        assertTrue(finished.isCompleted)
        mouth.stop()
    }

    @Test
    fun streamingNeedsTheSynthesisGrantAndSendsNothingWithoutIt() = streamTest {
        val ungranted = EndpointSpeechSynthesizer(client, { VoiceEndpointGrants() }, settings)
        val refused = assertFailsWith<SpeechEndpointException> { ungranted.stream(VoiceText("hi")).toList() }
        assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, refused.code)
        assertEquals(0, requests)
    }

    @Test
    fun aResponseThatIsNotWaveFailsTheSegmentWithBadResponse() = streamTest { scope ->
        val player = StreamingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        val failed = scope.launchFirst(mouth) { it is VoiceMouthEvent.Failed }
        mouth.speak(segment(1))
        val body = nextBody()
        body.writeFully("ID3".encodeToByteArray() + ByteArray(64))
        body.close()
        withTimeout(WAIT) { failed.join() }
        assertEquals(VoiceErrorCode.BAD_RESPONSE, (failed.event as VoiceMouthEvent.Failed).error.code)
        assertTrue(player.received.isEmpty(), "nothing undecodable is played")
        mouth.stop()
    }

    /**
     * A coroutine, already subscribed when this returns, that completes once [mouth] reports an
     * event matching [match]; it records that event.
     */
    private suspend fun CoroutineScope.launchFirst(mouth: EndpointVoiceMouth, match: (VoiceMouthEvent) -> Boolean): EventWait {
        val wait = EventWait()
        val subscribed = CompletableDeferred<Unit>()
        wait.job = launch { wait.event = mouth.events.onSubscription { subscribed.complete(Unit) }.first(match) }
        withTimeout(WAIT) { subscribed.await() }
        return wait
    }

    /** A pending wait for one mouth event. */
    private class EventWait {
        lateinit var job: Job
        var event: VoiceMouthEvent? = null
        val isCompleted: Boolean get() = job.isCompleted

        suspend fun join() = job.join()
    }

    /**
     * Collects each stream as it arrives: every chunk goes to [chunks] at once; once the stream has
     * ended it holds the segment until [playedOut] opens, like a speaker still playing its tail.
     */
    private class StreamingPlayer : AudioPlayer {
        // overflow: test-only.
        val chunks = Channel<ShortArray>(Channel.UNLIMITED)
        val received = mutableListOf<Short>()
        val formats = mutableSetOf<PcmFormat>()
        val playedOut = CompletableDeferred<Unit>()
        val streamEnded = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
            var finished = false
            try {
                chunks.collect { chunk ->
                    formats += chunk.format
                    received += chunk.samples.toList()
                    onLevel(AudioLevel(0.5f))
                    this.chunks.send(chunk.samples)
                }
                streamEnded.complete(Unit)
                playedOut.await()
                finished = true
            } finally {
                if (!finished) cancelled.complete(Unit)
            }
        }

        /** The next [count] samples to arrive, however the stream split them into chunks. */
        suspend fun nextSamples(count: Int): ShortArray = withTimeout(WAIT) {
            val out = mutableListOf<Short>()
            while (out.size < count) out += chunks.receive().toList()
            out.toShortArray()
        }
    }

    private companion object {
        const val RATE = 24_000
        val WAIT = 5.seconds
        val PAUSE = 10.milliseconds
        val SETTLE = 150.milliseconds
        val FIRST = ShortArray(240) { (it * 37 - 4_000).toShort() }
        val SECOND = ShortArray(160) { (3_000 - it * 11).toShort() }

        fun samplesOf(segment: Int) = ShortArray(120) { (segment * 1_000 + it).toShort() }

        /** The speech server's streamed WAVE header: 24 kHz mono 16-bit, RIFF and data sizes 0xFFFFFFFF. */
        fun streamedHeader(): ByteArray {
            val header = WavCodec.encode(PcmAudio(ShortArray(0), PcmFormat(RATE)))
            for (field in listOf(4, 40)) for (byte in 0 until 4) header[field + byte] = 0xFF.toByte()
            return header
        }

        fun bytesOf(samples: ShortArray): ByteArray = ByteArray(samples.size * 2) { index ->
            val sample = samples[index / 2].toInt()
            (if (index % 2 == 0) sample else sample shr 8).toByte()
        }
    }
}
