package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.AdminRpcBiStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcRecvStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcSendStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamServer
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one voice-endpoint fixture case the way a paired device reaches the host: a request bi-stream
 * into the real [AdminRpcStreamServer], through the real capability gate, into the real voice routes,
 * whose speech server is a Ktor mock answering as the case's `host_state.voice.upstream` says. No
 * network, no real audio: the fake server sends WAVE headers and a few bytes of silence. Returns every
 * frame the host wrote, the final response last.
 */
internal object VoiceContractHarness {
    private const val PLACEHOLDER_KEY = "placeholder-key-not-real"
    private const val DEFAULT_CLOCK_MS = 1_767_225_600_000L

    /** How long after the client cancels the fake server must see its request closed. */
    const val CANCEL_BUDGET_MS = 250L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(method: String, case: JsonObject): List<JsonObject> = withContext(Dispatchers.Default) {
        withTimeout(20.seconds) { Run(method, case).execute() }
    }

    private class Run(private val method: String, private val case: JsonObject) {
        private val voice = case.getValue("host_state").jsonObject.getValue("voice").jsonObject
        private val upstreamSpec = voice["upstream"]?.jsonObject ?: JsonObject(emptyMap())
        private val capabilities = case.getValue("context").jsonObject.getValue("capabilities").jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        private val clientFrames = case["client_frames"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        private val fake = FakeSpeechServer(upstreamSpec)

        suspend fun execute(): List<JsonObject> = coroutineScope {
            val router = AdminRpcRouter()
            val upstream = KtorVoiceSpeechUpstream(MockEngine { fake.answer(this, it) }, TEST_TIMEOUTS)
            if (voice["contract_served"]?.jsonPrimitive?.contentOrNull != "false") {
                VoiceRoutes(store(), upstream, VoiceInFlight(), clock()).register(router)
            }
            val server = AdminRpcStreamServer(
                router = router,
                authenticated = AtomicBoolean(true),
                peerSupportsFrameParts = { true },
                requestContextProvider = { AdminRpcRequestContext(authenticated = true, grantedCapabilities = capabilities) },
                capabilityGate = { name ->
                    val required = IrohPeerCapabilities.forAdminMethod(name)
                    if (IrohPeerCapabilities.isAllowed(capabilities, required)) null else required
                },
            )
            try {
                val blocker = occupySlot(server)
                val frames = exchange(server, "contract", params())
                blocker?.let { it.cancel(); it.job.cancelAndJoin() }
                frames
            } finally {
                upstream.close()
            }
        }

        private fun store(): VoiceSetupStore {
            val keys = InMemoryVoiceKeyStore()
            val stored = voice["stored"]?.takeIf { it is JsonObject }?.jsonObject
            val state = stored?.let { fields ->
                val setup = json.decodeFromJsonElement(StoredVoiceSetup.serializer(), fields)
                if (fields["has_api_key"]?.jsonPrimitive?.contentOrNull == "true") keys.write(setup.origin, PLACEHOLDER_KEY)
                VoiceSetupState(fields.getValue("revision").jsonPrimitive.content.removePrefix("rev-").toLong(), setup)
            } ?: VoiceSetupState()
            return VoiceSetupStore(InMemoryVoiceSetupPersistence(state), keys, clock())
        }

        private fun clock(): () -> Long {
            val now = voice["clock_ms"]?.jsonPrimitive?.long ?: DEFAULT_CLOCK_MS
            return { now }
        }

        /** The case's params, with an oversized clip or text built in place of the placeholder. */
        private fun params(): JsonObject {
            val params = case.getValue("params").jsonObject
            val audioBytes = voice["audio_override"]?.jsonObject?.get("decoded_bytes")?.jsonPrimitive?.int
            val textChars = voice["text_override"]?.jsonObject?.get("chars")?.jsonPrimitive?.int
            return when {
                audioBytes != null -> JsonObject(params + ("audio" to JsonPrimitive(Base64.encode(silentWav(audioBytes)))))
                textChars != null -> JsonObject(params + ("text" to JsonPrimitive("a".repeat(textChars))))
                else -> params
            }
        }

        /** Holds the slot the case says is in flight, with a real request on the same connection. */
        private suspend fun CoroutineScope.occupySlot(server: AdminRpcStreamServer): ClientStream? {
            val inFlight = voice["in_flight"]?.jsonObject ?: return null
            val blocked = inFlight.keys.single()
            fake.blockNext = true
            val params = when (blocked) {
                "voice.speech" -> buildJsonObject { put("text", "Placeholder reply.") }
                else -> buildJsonObject { put("audio", SILENT_WAV_BASE64); put("encoding", "wav") }
            }
            val stream = ClientStream(blocked, "blocker", params)
            stream.job = launch { server.handleStream(stream) }
            fake.blockerArrived.await()
            return stream
        }

        private suspend fun CoroutineScope.exchange(server: AdminRpcStreamServer, requestId: String, params: JsonObject): List<JsonObject> {
            val stream = ClientStream(method, requestId, params)
            val serving = launch { server.handleStream(stream) }
            val frames = mutableListOf<JsonObject>()
            for (frame in stream.received) {
                frames += frame
                cancelAfter(frame, stream)
                if (frame["type"]?.jsonPrimitive?.content == "admin_rpc_response") break
                if (frame["seq"]?.jsonPrimitive?.int == 1) fake.secondAudioAllowed.complete(Unit)
            }
            stream.finish()
            serving.join()
            checkUpstreamClosed()
            return frames
        }

        private var cancelSentAt = 0L

        private suspend fun cancelAfter(frame: JsonObject, stream: ClientStream) {
            val seq = frame["seq"]?.jsonPrimitive?.int ?: return
            clientFrames.filter { it.getValue("after_seq").jsonPrimitive.int == seq }.forEach {
                cancelSentAt = System.nanoTime()
                stream.send(it.getValue("frame").jsonObject)
            }
        }

        /** The fake server records when its request was closed; that time, not this check's, is measured. */
        private suspend fun checkUpstreamClosed() {
            if (case["expected_upstream"]?.jsonPrimitive?.contentOrNull != "closed") return
            withTimeoutOrNull(2.seconds) { while (fake.speechClosedAt.get() == null) delay(5.milliseconds) }
            val closedAt = checkNotNull(fake.speechClosedAt.get()) { "${case["name"]}: the upstream request was never closed" }
            val millis = (closedAt - cancelSentAt) / 1_000_000L
            assertTrue(millis <= CANCEL_BUDGET_MS, "${case["name"]}: upstream closed ${millis} ms after the cancel")
        }
    }

    /** The device's end of one request bi-stream. Its send half stays open until [finish]. */
    private class ClientStream(method: String, requestId: String, params: JsonObject) : AdminRpcBiStream {
        private val toHost = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = IrohFrameCodec.Decoder(IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES)
        private val id = requestId
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        lateinit var job: kotlinx.coroutines.Job

        init {
            send(
                buildJsonObject {
                    put("type", "admin_rpc")
                    put("request_id", requestId)
                    put("method", method)
                    put("params", params)
                },
            )
        }

        /** One frame, as `frame_part` continuations when it is over one Iroh frame (a full-size clip is). */
        fun send(frame: JsonObject) {
            IrohFrameCodec.encodeFrameParts(frame.toString()).forEach { toHost.trySend(it) }
        }

        fun cancel() = send(buildJsonObject { put("type", "admin_rpc_cancel"); put("request_id", id) })

        fun finish() {
            toHost.close()
        }

        override fun recv(): AdminRpcRecvStream = object : AdminRpcRecvStream {
            override suspend fun read(maxBytes: UInt): ByteArray = toHost.receiveCatching().getOrNull() ?: ByteArray(0)
        }

        override fun send(): AdminRpcSendStream = object : AdminRpcSendStream {
            override suspend fun writeAll(bytes: ByteArray) {
                decoder.feed(bytes).forEach { received.send(Json.parseToJsonElement(it).jsonObject) }
            }

            override suspend fun finish() {
                received.close()
            }
        }
    }

    /** A speech server double driven by a case's `upstream` description. */
    private class FakeSpeechServer(private val spec: JsonObject) {
        @Volatile
        var blockNext = false
        val blockerArrived = CompletableDeferred<Unit>()
        val secondAudioAllowed = CompletableDeferred<Unit>()
        val speechClosedAt = AtomicReference<Long?>(null)
        private val violations = mutableListOf<String>()

        suspend fun answer(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            if (blockNext) return block(scope, request)
            check(spec.isNotEmpty()) { "The host called the speech server in a case that must not reach it" }
            return refusal(scope, request) ?: route(scope, request)
        }

        /** Holds the slot-occupying request open until the test cancels it. */
        private suspend fun block(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            blockNext = false
            blockerArrived.complete(Unit)
            if (request.url.encodedPath.endsWith("/speech")) return scope.respond(ByteChannel(autoFlush = true))
            awaitCancellation()
        }

        /** A refused connection, a stall, a key where none may go, or a fixed status; null to route the call. */
        private suspend fun refusal(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData? {
            when (spec["connect"]?.jsonPrimitive?.contentOrNull) {
                "refused" -> throw java.net.ConnectException("refused")
                "stalls" -> awaitCancellation()
            }
            val keyForbidden = spec["expect_authorization"]?.jsonPrimitive?.contentOrNull == "false"
            if (keyForbidden && request.headers[HttpHeaders.Authorization] != null) {
                violations += "authorization sent"
                return scope.respond("", HttpStatusCode.InternalServerError)
            }
            return spec["status"]?.jsonPrimitive?.int?.let { scope.respond("", HttpStatusCode.fromValue(it)) }
        }

        private suspend fun route(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath
            return when {
                path.endsWith("/v1/models") -> scope.jsonReply(listing("data", spec["models"]))
                path.endsWith("/v1/audio/voices") -> voices(scope)
                path.endsWith("/v1/audio/transcriptions") -> transcription(scope, request)
                path.endsWith("/v1/audio/speech") -> speech(scope)
                else -> error("Unexpected speech server path")
            }
        }

        private fun voices(scope: MockRequestHandleScope): HttpResponseData =
            spec["voices_status"]?.jsonPrimitive?.int?.let { scope.respond("", HttpStatusCode.fromValue(it)) }
                ?: scope.jsonReply(buildJsonObject { put("voices", spec["voices"] ?: JsonArray(emptyList())) })

        private suspend fun transcription(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            val verbose = request.body.toByteArray().decodeToString().contains("verbose_json")
            return when (spec["transcription"]?.jsonPrimitive?.content) {
                "verbose_json" -> scope.jsonReply(Json.parseToJsonElement(VERBOSE_TRANSCRIPT).jsonObject)
                "json_only" -> if (verbose) {
                    scope.respond("", HttpStatusCode.BadRequest)
                } else {
                    scope.jsonReply(buildJsonObject { put("text", "placeholder words") })
                }
                else -> error("Unexpected transcription")
            }
        }

        /** A WAVE body the test writes piece by piece, as the case's `speech` says. */
        private fun speech(scope: MockRequestHandleScope): HttpResponseData {
            val body = ByteChannel(autoFlush = true)
            val header = wavHeader(spec.getValue("sample_rate_hz").jsonPrimitive.int, spec.getValue("channels").jsonPrimitive.int)
            val writer: suspend () -> Unit = when (spec.getValue("speech").jsonPrimitive.content) {
                "wav_stream" -> { -> twoRuns(body, header) }
                "wav_stream_slow" -> { -> oneRunThenStall(body, header) }
                "wav_header_then_garbage" -> { -> body.writeFully(header + ByteArray(1)); body.close() }
                else -> error("Unexpected speech body")
            }
            CoroutineScope(Dispatchers.Default).launch { writer() }
            return scope.respond(body)
        }

        /** Four samples, then two more once the host has written the first audio frame. */
        private suspend fun twoRuns(body: ByteChannel, header: ByteArray) {
            body.writeFully(header + ByteArray(8))
            secondAudioAllowed.await()
            body.writeFully(ByteArray(4))
            body.close()
        }

        /** Four samples, then nothing until the host closes the request; records when it did. */
        private suspend fun oneRunThenStall(body: ByteChannel, header: ByteArray) {
            body.writeFully(header + ByteArray(8))
            while (!body.isClosedForWrite) delay(2.milliseconds)
            speechClosedAt.compareAndSet(null, System.nanoTime())
        }

        private fun listing(field: String, ids: kotlinx.serialization.json.JsonElement?): JsonObject = buildJsonObject {
            put(field, JsonArray(ids?.jsonArray.orEmpty().map { buildJsonObject { put("id", it) } }))
        }

        private fun MockRequestHandleScope.jsonReply(body: JsonObject): HttpResponseData {
            check(violations.isEmpty()) { violations.joinToString() }
            return respond(body.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private const val VERBOSE_TRANSCRIPT =
        """{"text":" placeholder words","segments":[""" +
            """{"text":"placeholder","no_speech_prob":0.02,"avg_logprob":-0.25},""" +
            """{"text":"words","no_speech_prob":0.9,"avg_logprob":-1.5}]}"""

    private val TEST_TIMEOUTS = VoiceUpstreamTimeouts(
        connect = 2.seconds,
        firstByte = 2.seconds,
        transcription = 3.seconds,
        synthesis = 10.seconds,
        reachability = 2.seconds,
    )

    private val SILENT_WAV_BASE64 = Base64.encode(silentWav(52))

    /** A mono 16 kHz WAVE of [totalBytes] bytes: a 44-byte header and silence. */
    fun silentWav(totalBytes: Int): ByteArray {
        val data = totalBytes - WAV_HEADER_BYTES
        return wavHeader(16_000, 1, data.toLong()) + ByteArray(data)
    }

    /** A 16-bit PCM WAVE header; without [dataBytes] its size is the streaming placeholder. */
    fun wavHeader(rateHz: Int, channels: Int, dataBytes: Long = 0xFFFFFFFFL): ByteArray {
        val out = java.nio.ByteBuffer.allocate(WAV_HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt((dataBytes + 36).toInt()).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rateHz)
        out.putInt(rateHz * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        out.put("data".toByteArray()).putInt(dataBytes.toInt())
        return out.array()
    }

    private const val WAV_HEADER_BYTES = 44
}
