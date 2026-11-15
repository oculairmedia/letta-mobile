package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.AdminRpcBiStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRecvStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcSendStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamServer
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.data.voice.host.HostVoiceGrants
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/**
 * `voice.speech` cancellation and framing through the real stream server, beyond the fixture's cancel
 * frame: the client's send half finishing or resetting, a failed write, the slot released before the
 * final response, the 64 KiB frame cap and the control-channel refusal. The speech server is a fake
 * behind the upstream seam; it records when its call was cancelled.
 */
class VoiceSpeechStreamTest {
    private val inFlight = VoiceInFlight()
    private val upstream = StallingUpstream()
    private val router = AdminRpcRouter().also { router ->
        val setup = StoredVoiceSetup(
            baseUrl = "https://speech.example.invalid",
            speechModel = "tts-model-a",
            voice = "voice-a",
            grants = HostVoiceGrants(textForSynthesis = true),
            grantedAtMs = 1L,
        )
        val store = VoiceSetupStore(InMemoryVoiceSetupPersistence(VoiceSetupState(1, setup)), InMemoryVoiceKeyStore()) { 1L }
        VoiceRoutes(store, upstream, inFlight) { 1L }.register(router)
    }
    private val server = AdminRpcStreamServer(
        router = router,
        authenticated = AtomicBoolean(true),
        requestContextProvider = { AdminRpcRequestContext(authenticated = true) },
    )

    @Test
    fun finishingTheSendHalfCancelsTheUpstreamCallWithinBudget() = cancelledBy { it.finishSending() }

    @Test
    fun resettingTheSendHalfCancelsTheUpstreamCallWithinBudget() = cancelledBy { it.resetSending() }

    @Test
    fun aFailedWriteCancelsTheUpstreamCallAndReleasesTheSlot() = streamTest {
        val stream = Client(failWritesAfter = 1)
        val serving = launch { server.handleStream(stream) }
        serving.join()
        assertTrue(upstream.cancelledAt.get() > 0, "the upstream call was cancelled")
        assertEquals(0, inFlight.heldCount)
    }

    @Test
    fun theSlotIsFreeBeforeTheFinalResponseArrives() = streamTest {
        upstream.finishAfterHeader = true
        val stream = Client(onFinalWritten = { heldWhenFinal = inFlight.heldCount })
        val serving = launch { server.handleStream(stream) }
        val frames = stream.untilFinal { }
        serving.join()
        assertEquals(true, frames.last().getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(0, heldWhenFinal, "the slot was free when the final response was written")
    }

    @Test
    fun aStreamShorterThanItsDeclaredDataIsABadResponse() = streamTest {
        upstream.finishAfterHeader = true
        upstream.declaredBytes = 100L
        val stream = Client()
        val serving = launch { server.handleStream(stream) }
        val frames = stream.untilFinal { }
        serving.join()
        assertEquals("voice.upstream_bad_response", frames.last().getValue("error").jsonPrimitive.content)
    }

    @Test
    fun largeRunsAreSplitIntoWholeFramesOfAtMost64KiB() = streamTest {
        upstream.finishAfterHeader = true
        upstream.payloadBytes = 150_000
        val stream = Client()
        val serving = launch { server.handleStream(stream) }
        val frames = stream.untilFinal { }
        serving.join()
        val audio = frames.filter { it["event"]?.jsonPrimitive?.content == "audio" }
            .map { Base64.decode(it.getValue("pcm").jsonPrimitive.content) }
        assertTrue(audio.all { it.size <= 65_536 && it.size % 2 == 0 }, "whole frames of at most 64 KiB")
        assertEquals(150_000, audio.sumOf { it.size })
        assertEquals(frames.indices.toList().dropLast(1), frames.dropLast(1).map { it.getValue("seq").jsonPrimitive.int })
    }

    @Test
    fun onTheControlChannelSpeechIsAMalformedRequest(): Unit = runBlocking {
        val response = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("r", "voice.speech", buildJsonObject { put("text", "Placeholder reply.") })),
        ).jsonObject
        assertEquals("voice.invalid_request", response.getValue("error").jsonPrimitive.content)
        assertEquals(0L, upstream.calls.get())
    }

    @Test
    fun theHostAllowsFourUpstreamSyntheses() {
        val limits = VoiceInFlight()
        val held = (1..4).map { checkNotNull(limits.tryAcquire(java.util.UUID.randomUUID().toString(), VoiceRequestKind.SPEECH)) }
        assertEquals(null, limits.tryAcquire(java.util.UUID.randomUUID().toString(), VoiceRequestKind.SPEECH))
        val connection = "connection-a"
        held.first().release()
        val slot = checkNotNull(limits.tryAcquire(connection, VoiceRequestKind.SPEECH))
        assertEquals(null, limits.tryAcquire(connection, VoiceRequestKind.SPEECH), "one per connection")
        assertTrue(limits.tryAcquire(connection, VoiceRequestKind.TRANSCRIPTION) != null, "the other kind is separate")
        slot.release()
        slot.release()
        held.drop(1).forEach { it.release() }
    }

    private var heldWhenFinal = -1

    private fun cancelledBy(cancel: (Client) -> Unit) = streamTest {
        val stream = Client()
        val serving = launch { server.handleStream(stream) }
        val frames = stream.untilFinal { frame ->
            if (frame["seq"]?.jsonPrimitive?.int == 1) {
                upstream.cancelRequestedAt.set(System.nanoTime())
                cancel(stream)
            }
        }
        serving.join()
        assertEquals("voice.cancelled", frames.last().getValue("error").jsonPrimitive.content)
        val millis = (upstream.cancelledAt.get() - upstream.cancelRequestedAt.get()) / 1_000_000L
        assertTrue(upstream.cancelledAt.get() > 0 && millis <= VoiceContractHarness.CANCEL_BUDGET_MS, "cancelled after $millis ms")
        assertEquals(0, inFlight.heldCount)
    }

    private fun streamTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { coroutineScope { block() } } }
    }

    /** Sends a WAVE header and silence, then stalls until cancelled (or finishes when told to). */
    private class StallingUpstream : VoiceSpeechUpstream {
        val calls = AtomicLong(0)
        val cancelRequestedAt = AtomicLong(0)
        val cancelledAt = AtomicLong(0)

        @Volatile
        var finishAfterHeader = false

        @Volatile
        var payloadBytes = 8

        @Volatile
        var declaredBytes = 0xFFFFFFFFL

        override suspend fun speech(target: VoiceUpstreamTarget, request: VoiceSpeechCall, onBytes: suspend (ByteArray) -> Unit) {
            calls.incrementAndGet()
            var finished = false
            try {
                onBytes(VoiceContractHarness.wavHeader(24_000, 1, declaredBytes) + ByteArray(payloadBytes))
                if (!finishAfterHeader) awaitCancellation()
                finished = true
            } finally {
                if (!finished) cancelledAt.set(System.nanoTime())
            }
        }

        override suspend fun models(target: VoiceUpstreamTarget): List<String> = error("unused")

        override suspend fun voices(target: VoiceUpstreamTarget): List<String>? = error("unused")

        override suspend fun transcribe(target: VoiceUpstreamTarget, request: VoiceTranscriptionCall) = error("unused")

        override fun close() = Unit
    }

    /** The device's end of a `voice.speech` request stream. */
    private class Client(
        private val failWritesAfter: Int = Int.MAX_VALUE,
        private val onFinalWritten: () -> Unit = {},
    ) : AdminRpcBiStream {
        private val toHost = Channel<ByteArray>(Channel.UNLIMITED)
        private val reset = CompletableDeferred<Unit>()
        private val decoder = IrohFrameCodec.Decoder(IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES)
        private val received = Channel<JsonObject>(Channel.UNLIMITED)
        private var writes = 0

        init {
            val request = buildJsonObject {
                put("type", "admin_rpc")
                put("request_id", "speech-1")
                put("method", "voice.speech")
                put("params", buildJsonObject { put("text", "Placeholder reply.") })
            }
            toHost.trySend(IrohFrameCodec.encodeFrame(request.toString()))
        }

        fun finishSending() {
            toHost.close()
        }

        fun resetSending() {
            reset.complete(Unit)
        }

        suspend fun untilFinal(onFrame: (JsonObject) -> Unit): List<JsonObject> {
            val frames = mutableListOf<JsonObject>()
            for (frame in received) {
                if (frame["type"]?.jsonPrimitive?.content == "admin_rpc_response") onFrame(frame)
                frames += frame
                if (frame["type"]?.jsonPrimitive?.content == "admin_rpc_response") break
                onFrame(frame)
            }
            finishSending()
            return frames
        }

        override fun recv(): AdminRpcRecvStream = object : AdminRpcRecvStream {
            override suspend fun read(maxBytes: UInt): ByteArray = coroutineScope {
                val next = kotlinx.coroutines.selects.select {
                    toHost.onReceiveCatching { it.getOrNull() ?: ByteArray(0) }
                    reset.onAwait { null }
                }
                next ?: throw java.io.IOException("stream reset")
            }
        }

        override fun send(): AdminRpcSendStream = object : AdminRpcSendStream {
            override suspend fun writeAll(bytes: ByteArray) {
                if (++writes > failWritesAfter) throw java.io.IOException("send failed")
                decoder.feed(bytes).forEach { text ->
                    val frame = Json.parseToJsonElement(text).jsonObject
                    if (frame["type"]?.jsonPrimitive?.content == "admin_rpc_response") onFinalWritten()
                    received.send(frame)
                }
            }

            override suspend fun finish() {
                received.close()
            }
        }
    }
}
