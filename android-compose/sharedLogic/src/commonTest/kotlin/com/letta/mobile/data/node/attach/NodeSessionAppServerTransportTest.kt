package com.letta.mobile.data.node.attach

import com.letta.mobile.data.node.carrier.InProcessNodeCarrier
import com.letta.mobile.data.node.carrier.NodeCredential
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeOpenRequest
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.data.node.carrier.NodeSession
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The transport delivers the session's control and push lanes independently, so neither can hold back the other. */
class NodeSessionAppServerTransportTest {
    @Test
    fun aSlowPushSubscriberNeverHoldsBackAControlAnswer() = onNode { transport, node ->
        val pushReceived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A push subscriber that takes the first frame and then stalls, as a busy UI collector would.
        val stalled = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.streamFrames.collect {
                pushReceived.complete(Unit)
                release.await()
            }
        }
        val control = async(start = CoroutineStart.UNDISPATCHED) { transport.controlFrames.first() }
        node.pushThenAnswer(PUSHES)
        pushReceived.await()
        assertEquals("sync_response", control.await().type(), "the control answer arrives behind a stalled push subscriber")
        release.complete(Unit)
        stalled.cancel() // A shared flow's collection never ends by itself.
    }

    @Test
    fun everyPushStillArrivesInOrder() = onNode { transport, node ->
        val pushes = async(start = CoroutineStart.UNDISPATCHED) { transport.streamFrames.take(PUSHES).toList() }
        node.pushThenAnswer(PUSHES)
        assertEquals((0 until PUSHES).map { "push-$it" }, pushes.await().map { it.raw["request_id"]?.jsonPrimitive?.content })
    }

    private fun onNode(body: suspend CoroutineScope.(NodeSessionAppServerTransport, PushingNode) -> Unit) =
        runTest(timeout = TIMEOUT) {
            withContext(Dispatchers.Default) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val node = PushingNode()
                    val carrier = InProcessNodeCarrier("transport-node", NodePeerIdentity("node"), node, scope)
                    val session = NodeSession.open(carrier, NodeOpenRequest(carrier.address, NodeCredential.CarrierIdentity), scope)
                    val transport = NodeSessionAppServerTransport(session, scope, appToolRelay = null)
                    withTimeout(TIMEOUT) { body(transport, node) }
                } finally {
                    scope.cancel()
                }
            }
        }

    private fun AppServerReceivedFrame.type(): String? = raw["type"]?.jsonPrimitive?.content

    private companion object {
        /** Well past the transport's 64-frame subscriber buffer. */
        const val PUSHES = 200
        val TIMEOUT = 20.seconds
    }
}

/** Accepts the shell's auth, then on cue writes a burst of pushes followed by one unsolicited control answer. */
private class PushingNode : NodePeerAcceptor {
    private val link = CompletableDeferred<NodePeerLink>()

    override suspend fun serve(link: NodePeerLink) {
        this.link.complete(link)
        link.control.collect { frame ->
            if (frame.type == "auth") link.sendControl(authAccepted(frame.requestId.orEmpty()))
        }
    }

    suspend fun pushThenAnswer(pushes: Int) {
        val link = link.await()
        repeat(pushes) { link.push(frame("stream_delta", "push-$it")) }
        link.sendControl(frame("sync_response", "sync-1"))
    }

    private fun frame(type: String, requestId: String) = NodeFrame(
        buildJsonObject {
            put("type", type)
            put("request_id", requestId)
        },
    )

    private fun authAccepted(requestId: String) = NodeFrame(
        buildJsonObject {
            put("type", "auth_response")
            put("request_id", requestId)
            put("success", true)
            put("capabilities", JsonArray(emptyList()))
        },
    )
}
