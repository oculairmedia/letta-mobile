package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.controller.node.iroh.AdminRpcBiStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcRecvStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcSendStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamServer
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceTestStatus
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one service-credentials fixture case the way a paired device reaches the host: a request
 * bi-stream into the real [AdminRpcStreamServer], through the real capability gate, into the real
 * routes over in-memory stores seeded from `host_state.service_credentials`. TypeSafe answers from a
 * Ktor mock as the case's `upstream` says, and every request it gets must be the synthetic probe with
 * the placeholder key. Returns the host's final response.
 */
internal object ServiceCredentialContractHarness {
    const val PLACEHOLDER_SECRET = "placeholder-key-not-real"
    private const val DEFAULT_CLOCK_MS = 1_767_225_600_000L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(method: String, case: JsonObject): JsonObject = withContext(Dispatchers.Default) {
        withTimeout(20.seconds) { execute(method, case) }
    }

    private suspend fun execute(method: String, case: JsonObject): JsonObject = coroutineScope {
        val host = case.getValue("host_state").jsonObject.getValue("service_credentials").jsonObject
        val capabilities = case.getValue("context").jsonObject.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val clock = host["clock_ms"]?.jsonPrimitive?.long ?: DEFAULT_CLOCK_MS
        val router = AdminRpcRouter()
        ServiceCredentialRouteSet(store(host), clock = { clock }).register(router)
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
        val stream = ClientStream(method, case.getValue("params").jsonObject)
        val serving = launch { server.handleStream(stream) }
        val response = stream.received.receive()
        stream.finish()
        serving.join()
        response
    }

    private fun store(host: JsonObject): ServiceCredentialStore {
        val secrets = InMemoryServiceSecretStore()
        val adapter = TypeSafeJevAdapter(fakeTypeSafe(host["upstream"]?.jsonObject))
        val stored = host["stored"]?.jsonObject.orEmpty().mapValues { (id, element) ->
            val fields = element.jsonObject
            val credential = StoredServiceCredential(
                revision = fields.getValue("revision").jsonPrimitive.content.removePrefix("rev-").toLong(),
                baseUrl = fields["base_url"]?.jsonPrimitive?.content,
                config = fields["config"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
                lastTest = fields["last_test"]
                    ?.let { json.decodeFromJsonElement(ServiceTestStatus.serializer(), it) } ?: ServiceTestStatus(),
            )
            if (fields["has_secret"]?.jsonPrimitive?.content == "true") {
                val address = credential.baseUrl ?: requireNotNull(adapter.descriptor.defaultBaseUrl)
                secrets.write(ServiceId(id), BoundSecret(requireNotNull(ServiceAddress.originOf(address)), PLACEHOLDER_SECRET))
            }
            credential
        }
        val state = ServiceCredentialState(stored.values.maxOfOrNull { it.revision } ?: 0, stored)
        return ServiceCredentialStore(listOf(adapter), InMemoryServiceCredentialPersistence(state), secrets)
    }

    /** TypeSafe as the case's `upstream` describes; a case without one must never reach it. */
    private fun fakeTypeSafe(upstream: JsonObject?) = MockEngine { request ->
        checkNotNull(upstream) { "The host called TypeSafe in a case that must not reach it" }
        assertEquals("/v1/systemone", request.url.encodedPath)
        assertEquals("Bearer $PLACEHOLDER_SECRET", request.headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
        val state = sent.getValue("state").jsonPrimitive.content
        assertEquals(TypeSafeJevAdapter.SYNTHETIC_TEXT, state, "Only synthetic text may go to TypeSafe")
        respond(
            content = upstream["body"]?.toString().orEmpty(),
            status = HttpStatusCode.fromValue(upstream.getValue("status").jsonPrimitive.int),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    /** The device's end of one request bi-stream. */
    private class ClientStream(method: String, params: JsonObject) : AdminRpcBiStream {
        private val toHost = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = IrohFrameCodec.Decoder(IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES)
        val received = Channel<JsonObject>(Channel.UNLIMITED)

        init {
            val frame = buildJsonObject {
                put("type", "admin_rpc")
                put("request_id", "contract")
                put("method", method)
                put("params", params)
            }
            IrohFrameCodec.encodeFrameParts(frame.toString()).forEach { toHost.trySend(it) }
        }

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
}
