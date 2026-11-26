package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeCarrierWire
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue

/**
 * The other direction of [IrohNodeCarrierCompatibilityTest]: today's client, [IrohAppServerTransport], unchanged,
 * against the Iroh carrier's node end ([serveIrohNodePeer]). The carrier must look like today's host on the wire:
 * auth on control, a push larger than one Iroh frame split into `frame_part`s once the peer advertised them (and
 * reassembled whole by the old client), a one-shot call answered on its own lane, and a streamed call whose
 * `admin_rpc_stream` frames arrive before its response and whose early stop reaches the node as `admin_rpc_cancel`
 * carrying the call's `request_id`, followed by the finished send half.
 *
 * Live QUIC, so opt-in: `./gradlew :sharedLogic:jvmTest -DrunIrohLiveE2E=true --tests '<this class>'`.
 */
class IrohNodePeerReverseCompatibilityTest {
    @BeforeTest
    fun requireOptIn() {
        assumeTrue("Live Iroh requires -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    @Test
    fun todaysClientTalksToTheCarriersNodeEndUnchanged() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val node = ScriptedLegacyPeerNode()
        val host = IrohLoopbackNode.start(node, scope)
        val shell = bindLoopbackEndpoint()
        try {
            withTimeout(TEST_TIMEOUT) {
                val transport = IrohAppServerTransport(
                    endpoint = shell,
                    remoteAddr = host.endpoint.addr(),
                    alpn = IrohAppServerTransportAdapter.DEFAULT_ALPN,
                    scope = scope,
                    appToolRelay = null,
                )
                transport.awaitConnectionReady()
                assertTodaysClientInteroperates(transport, node)
                transport.close()
            }
        } finally {
            withTimeoutOrNull(TEARDOWN_TIMEOUT) {
                shell.release()
                host.stop()
            }
            scope.cancel()
        }
    }

    private suspend fun assertTodaysClientInteroperates(
        transport: IrohAppServerTransport,
        node: ScriptedLegacyPeerNode,
    ) = coroutineScope {
        val authAnswer = async(start = CoroutineStart.UNDISPATCHED) {
            transport.controlFrames.first { it.raw.type() == NodeCarrierWire.AUTH_RESPONSE }
        }
        val bigPush = async(start = CoroutineStart.UNDISPATCHED) {
            transport.streamFrames.first { it.raw.type() == LARGE_TYPE }
        }
        transport.sendControl(AppServerCommand.Auth(requestId = "auth-1", token = "t", capabilities = listOf("frame_part")))
        assertEquals(true, (authAnswer.await().raw["success"] as? JsonPrimitive)?.contentOrNull?.toBoolean())
        assertTrue("frame_part" in transport.serverCapabilities, "capabilities: ${transport.serverCapabilities}")
        assertEquals(largeFrame().json, bigPush.await().raw, "a push over one Iroh frame arrives whole at today's client")

        val health = transport.adminRpc("health.check", "/health", null)
        assertTrue(health.success, "health.check: $health")

        val streamed = transport.adminRpcStream("voice.speech", buildJsonObject { put("text", "hi") }).take(2).toList()
        assertEquals(listOf("admin_rpc_stream", "admin_rpc_stream"), streamed.map { it.type() })
        val (cancel, finished) = node.streamedEnd.await()
        assertEquals("admin_rpc_cancel", cancel?.type(), "the early stop reaches the node as a cancel")
        assertEquals(node.streamedRequestId.await(), (cancel?.get("request_id") as? JsonPrimitive)?.contentOrNull)
        assertTrue(finished, "then the client finishes its send half")
    }

    private companion object {
        const val LARGE_TYPE = "contract_large"
        val TEST_TIMEOUT = 40.seconds
        val TEARDOWN_TIMEOUT = 10.seconds

        fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

        fun largeFrame(): NodeFrame = NodeFrame(
            buildJsonObject {
                put("type", LARGE_TYPE)
                put("body", "x".repeat(1_572_864))
            },
        )
    }

    /** Plays today's host for one connection: auth, a large push, a one-shot call and a streamed one. */
    private class ScriptedLegacyPeerNode : NodePeerAcceptor {
        val streamedRequestId = CompletableDeferred<String?>()

        /** What followed the streamed call's frames on its lane: the first frame (if any), and whether the lane then ended. */
        val streamedEnd = CompletableDeferred<Pair<JsonObject?, Boolean>>()

        override suspend fun serve(link: NodePeerLink) = coroutineScope {
            launch { link.calls.collect { lane -> launch { answer(lane) } } }
            link.control.collect { frame ->
                if (frame.type != NodeCarrierWire.AUTH) return@collect
                link.sendControl(authAccepted(frame.requestId.orEmpty()))
                val advertised = (frame.json[NodeCarrierWire.CAPABILITIES] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                link.onPeerCapabilities(advertised)
                link.push(largeFrame())
            }
        }

        private suspend fun answer(lane: NodeCallLane) {
            val request = lane.incoming.first()
            val requestId = request.requestId.orEmpty()
            when ((request.json["method"] as? JsonPrimitive)?.contentOrNull) {
                "voice.speech" -> {
                    streamedRequestId.complete(requestId)
                    repeat(2) { lane.send(streamFrame(requestId, it)) }
                    val rest = lane.incoming.toList()
                    streamedEnd.complete(rest.firstOrNull()?.json to true)
                    // The client has stopped reading; the node still closes its half as today's host does.
                    runCatching { lane.send(response(requestId, success = false)) }
                    runCatching { lane.finishSending() }
                }
                else -> {
                    lane.send(response(requestId, success = true))
                    lane.finishSending()
                }
            }
        }

        private fun authAccepted(requestId: String) = NodeFrame(
            buildJsonObject {
                put("type", NodeCarrierWire.AUTH_RESPONSE)
                put("request_id", requestId)
                put("success", true)
                put("capabilities", JsonArray(listOf(JsonPrimitive("frame_part"))))
            },
        )

        private fun streamFrame(requestId: String, index: Int) = NodeFrame(
            buildJsonObject {
                put("type", "admin_rpc_stream")
                put("request_id", requestId)
                putJsonObject("event") { put("index", index) }
            },
        )

        private fun response(requestId: String, success: Boolean) = NodeFrame(
            buildJsonObject {
                put("type", NodeCarrierWire.ADMIN_RPC_RESPONSE)
                put("request_id", requestId)
                put("success", success)
                putJsonObject("result") { }
            },
        )
    }
}
