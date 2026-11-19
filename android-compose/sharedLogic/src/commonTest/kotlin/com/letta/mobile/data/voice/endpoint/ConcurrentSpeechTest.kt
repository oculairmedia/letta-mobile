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
import com.letta.mobile.data.voice.engine.SpeechAudioMeter
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.tone.ToneCue
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The mouth with two requests in flight and segments prefetched ahead, over a mock endpoint whose
 * responses the test writes per segment (requests are matched by their text, since concurrent
 * requests may arrive in either order).
 */
class ConcurrentSpeechTest {
    private val base = SpeechEndpointUrl("https://tts.example.net")
    private val settings = VoiceEndpointSettings(baseUrl = base)
    private val granted = VoiceEndpointGrants(
        setOf(VoiceEndpointGrant(EndpointOrigin.of(base)!!, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS)),
    )
    private val lock = SynchronizedObject()
    private val requestBodies = mutableListOf<String>()
    private val paths = mutableListOf<String>()

    // overflow: test-only; each speech request's text and the body the test will write.
    private val arrivals = Channel<Pair<String, ByteChannel>>(Channel.UNLIMITED)
    private val arrived = mutableMapOf<String, ByteChannel>()

    private val client = SpeechEndpointClient(
        MockEngine { request ->
            val text = (request.body as? TextContent)?.text.orEmpty()
            synchronized(lock) {
                paths += request.url.encodedPath
                if (text.isNotEmpty()) requestBodies += text
            }
            if (!request.url.encodedPath.endsWith("/speech")) return@MockEngine respond("""{"data":[]}""")
            val body = ByteChannel(autoFlush = true)
            arrivals.send(text to body)
            respond(body)
        },
        SpeechEndpointConfig(base),
    )

    private val synthesizer = EndpointSpeechSynthesizer(client, { granted }, settings)

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

    private fun segment(id: Long) = SpeechSegment(SpeechSegmentId(id), VoiceText("Sentence $id."))

    /** The body of segment [id]'s request, waiting for it to arrive. */
    private suspend fun bodyOf(id: Long): ByteChannel = withTimeout(WAIT) {
        val key = "Sentence $id."
        while (arrived.keys.none { key in it }) {
            val (text, body) = arrivals.receive()
            arrived[text] = body
        }
        arrived.entries.first { key in it.key }.value
    }

    private fun speechRequests(): Int = synchronized(lock) { requestBodies.size }

    private suspend fun ByteChannel.answer(samples: ShortArray) {
        writeFully(streamedHeader() + bytesOf(samples))
        close()
    }

    @Test
    fun twoRequestsRunWhileTheFirstSegmentPlaysAndPlaybackKeepsSegmentOrder() = streamTest { scope ->
        val player = HoldingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        (1L..4L).forEach { mouth.speak(segment(it)) }
        val first = bodyOf(1)
        val second = bodyOf(2)
        delay(SETTLE)
        assertEquals(2, speechRequests(), "two requests in flight, the third waits for a free slot")
        second.answer(samplesOf(2))
        bodyOf(3)
        assertEquals(3, speechRequests(), "a finished response frees its slot for the next segment")
        assertTrue(player.streams.tryReceive().isFailure, "the second segment does not play before the first")
        first.answer(samplesOf(1))
        assertContentEquals(samplesOf(1), player.nextStream())
        player.release()
        assertContentEquals(samplesOf(2), player.nextStream(), "segments play in order whatever order their responses finished in")
        mouth.stop()
    }

    @Test
    fun stoppingClosesEveryRequestInFlightAndCutsPlayback() = streamTest { scope ->
        val player = HoldingPlayer()
        val mouth = EndpointVoiceMouth(synthesizer, player, scope)
        (1L..3L).forEach { mouth.speak(segment(it)) }
        val first = bodyOf(1)
        val second = bodyOf(2)
        first.answer(samplesOf(1))
        assertContentEquals(samplesOf(1), player.nextStream())
        mouth.stop()
        val atStop = speechRequests()
        assertTrue(player.cancelled.isCompleted, "playback was cut, not left to finish")
        withTimeout(WAIT) { while (!second.isClosedForWrite) delay(PAUSE) }
        delay(SETTLE)
        assertEquals(atStop, speechRequests(), "no request starts after the stop")
    }

    @Test
    fun theMeterHearsEachSegmentsAudioLengthAndTheSpeedIsSent() = streamTest { scope ->
        val player = HoldingPlayer()
        val measured = Channel<Measured>(Channel.UNLIMITED)
        val meter = SpeechAudioMeter { id, audio, complete -> measured.trySend(Measured(id, audio, complete)) }
        val options = MouthOptions(speed = { SpeechSpeed(1.25f) }, meter = meter)
        val mouth = EndpointVoiceMouth(synthesizer, player, scope, options)
        mouth.speak(segment(1))
        bodyOf(1).answer(ShortArray(RATE / 2))
        val final = withTimeout(WAIT) {
            var last: Measured
            do {
                last = measured.receive()
            } while (!last.complete)
            last
        }
        assertEquals(Measured(SpeechSegmentId(1), 500.milliseconds, complete = true), final)
        assertTrue(synchronized(lock) { requestBodies.single() }.contains("\"speed\":1.25"), "the speed goes to the endpoint")
        mouth.stop()
    }

    @Test
    fun aSegmentsToneGoesToTheEndpointAsInstructions() = streamTest { scope ->
        val mouth = EndpointVoiceMouth(synthesizer, HoldingPlayer(), scope)
        mouth.speak(SpeechSegment(SpeechSegmentId(1), VoiceText("Sentence 1."), ToneCue("slow, reassuring")))
        bodyOf(1).answer(samplesOf(1))
        assertTrue(synchronized(lock) { requestBodies.single() }.contains("\"instructions\":\"slow, reassuring\""))
        mouth.stop()
    }

    @Test
    fun aSegmentWithoutAToneSendsNoInstructions() = streamTest { scope ->
        val mouth = EndpointVoiceMouth(synthesizer, HoldingPlayer(), scope)
        mouth.speak(segment(1))
        bodyOf(1).answer(samplesOf(1))
        assertTrue("instructions" !in synchronized(lock) { requestBodies.single() })
        mouth.stop()
    }

    @Test
    fun normalSpeedIsNotSent() = streamTest { scope ->
        val mouth = EndpointVoiceMouth(synthesizer, HoldingPlayer(), scope)
        mouth.speak(segment(1))
        bodyOf(1).answer(samplesOf(1))
        assertTrue("speed" !in synchronized(lock) { requestBodies.single() })
        mouth.stop()
    }

    @Test
    fun warmingSendsNoTextAndNeedsTheSynthesisGrant() = streamTest {
        synthesizer.warm()
        assertEquals(listOf("/v1/models"), synchronized(lock) { paths.toList() })
        assertEquals(0, speechRequests(), "a warm-up carries no text")
        val ungranted = EndpointSpeechSynthesizer(client, { VoiceEndpointGrants() }, settings)
        val refused = assertFailsWith<SpeechEndpointException> { ungranted.warm() }
        assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, refused.code)
        assertEquals(1, synchronized(lock) { paths.size }, "nothing is sent without the grant")
    }

    @Test
    fun optionsRejectEmptyLimits() {
        assertFailsWith<IllegalArgumentException> { MouthOptions(maxInFlight = 0) }
        assertFailsWith<IllegalArgumentException> { MouthOptions(prefetch = 0) }
    }

    private data class Measured(val id: SpeechSegmentId, val audio: Duration, val complete: Boolean)

    /** Takes each stream whole, then holds it until [release] (the speaker still playing its tail). */
    private class HoldingPlayer : AudioPlayer {
        // overflow: test-only.
        val streams = Channel<ShortArray>(Channel.UNLIMITED)
        val cancelled = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        fun release() = gate.complete(Unit)

        suspend fun nextStream(): ShortArray = withTimeout(WAIT) { streams.receive() }

        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
            var finished = false
            try {
                val samples = mutableListOf<Short>()
                chunks.collect { samples += it.samples.toList() }
                streams.send(samples.toShortArray())
                gate.await()
                finished = true
            } finally {
                if (!finished) cancelled.complete(Unit)
            }
        }
    }

    private companion object {
        const val RATE = 24_000
        val WAIT = 5.seconds
        val PAUSE = 10.milliseconds
        val SETTLE = 150.milliseconds

        fun samplesOf(segment: Int) = ShortArray(120) { (segment * 1_000 + it).toShort() }

        /** A streamed WAVE header: 24 kHz mono 16-bit, RIFF and data sizes 0xFFFFFFFF. */
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
