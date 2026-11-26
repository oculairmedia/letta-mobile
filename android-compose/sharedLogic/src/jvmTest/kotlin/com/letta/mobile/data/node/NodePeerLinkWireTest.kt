package com.letta.mobile.data.node

import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.data.node.carrier.NodePeerReassemblyBound
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The node's serving seam over a fake carrier link, no network: what the node tells a reassembling carrier (one frame
 * until the peer authenticated and advertised `frame_part`, one frame again after it is deauthenticated, and the same
 * `frame_part` answer the admin server uses), a control-lane violation ending the connection, and the connection's
 * end reported exactly once.
 */
class NodePeerLinkWireTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpoint = IrohNodeEndpoint(
        appServerLinks = ::serveIrohCarrierLink,
        scope = scope,
        authPolicy = IrohAuthPolicy.BearerToken(TOKEN),
    ).apply { startTurnHost(FakeAppServerController()) }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun theNodeBoundsReassemblyByAuthAndSharesItsFramePartAnswer() = runBlocking {
        withTimeout(TIMEOUT) {
            val link = FakeLink()
            val closedReports = AtomicInteger()
            coroutineScope {
                val serving = launch { serve(link) { closedReports.incrementAndGet() } }
                val bound = link.bound.await()
                assertEquals(IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES, bound.maxBytes(), "one frame before auth")
                assertEquals(false, bound.frameParts())

                link.peerSends(auth("a-1", TOKEN))
                assertEquals(true, link.answerTo("a-1"), "the token is accepted")
                assertEquals(IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES, bound.maxBytes(), "authenticated and advertised frame_part")
                assertEquals(true, bound.frameParts(), "the link splits on the node's own answer")

                link.peerSends(auth("a-2", "wrong-$TOKEN"))
                assertEquals(false, link.answerTo("a-2"), "the wrong token is refused")
                assertEquals(IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES, bound.maxBytes(), "one frame again once deauthenticated")

                link.peerFinishesControl()
                serving.join()
            }
            assertEquals(1, closedReports.get(), "the connection's end is reported exactly once")
            assertNotNull(link.closure.value, "the node closes the link after serving it")
        }
    }

    @Test
    fun aViolationOnTheControlLaneEndsTheConnection() = runBlocking {
        withTimeout(TIMEOUT) {
            val link = FakeLink(control = flow { throw IrohFrameCodec.ProtocolException("bad frame_part header") })
            val closedReports = AtomicInteger()
            serve(link) { closedReports.incrementAndGet() }
            assertEquals(NodeLinkClosure.LOST, link.closure.first { it != null }, "the node ends the link")
            assertEquals(1, closedReports.get())
        }
    }

    private suspend fun serve(link: FakeLink, closedReport: () -> Unit) = coroutineScope {
        endpoint.serveWire(NodePeerLinkWire(link, this, closedReport = closedReport))
    }

    /** The two decisions the node hands a reassembling carrier. */
    private class Bound(val maxBytes: () -> Int, val frameParts: () -> Boolean)

    /** A peer link whose peer is the test: control frames in, control answers out, no call lanes. */
    private class FakeLink(control: Flow<NodeFrame>? = null) : NodePeerLink, NodePeerReassemblyBound {
        private val toNode = Channel<NodeFrame>(Channel.UNLIMITED)
        private val toPeer = Channel<NodeFrame>(Channel.UNLIMITED)
        private val closureState = MutableStateFlow<NodeLinkClosure?>(null)
        val bound = CompletableDeferred<Bound>()

        override val peer = NodePeerIdentity("%064x".format(7))
        override val control: Flow<NodeFrame> = control ?: toNode.receiveAsFlow()
        override val calls: Flow<NodeCallLane> = emptyFlow()
        override val closure: StateFlow<NodeLinkClosure?> = closureState

        fun peerSends(frame: NodeFrame) {
            toNode.trySend(frame)
        }

        fun peerFinishesControl() {
            toNode.close()
        }

        /** The `success` of the control answer to [requestId]. */
        suspend fun answerTo(requestId: String): Boolean? {
            while (true) {
                val frame = toPeer.receive()
                if (frame.requestId == requestId) return (frame.json["success"] as? JsonPrimitive)?.booleanOrNull
            }
        }

        override suspend fun sendControl(frame: NodeFrame) {
            toPeer.send(frame)
        }

        override suspend fun push(frame: NodeFrame) = Unit

        override fun onPeerCapabilities(capabilities: Set<String>) = Unit

        override suspend fun close(closure: NodeLinkClosure) {
            closureState.compareAndSet(null, closure)
            toNode.close()
        }

        override fun boundReassembly(maxBytes: () -> Int, frameParts: () -> Boolean) {
            bound.complete(Bound(maxBytes, frameParts))
        }
    }

    private companion object {
        const val TOKEN = "node-peer-link-wire-test-token"
        val TIMEOUT = 20.seconds

        fun auth(requestId: String, token: String) = NodeFrame(
            buildJsonObject {
                put("type", "auth")
                put("request_id", requestId)
                put("token", token)
                put("capabilities", JsonArray(listOf(JsonPrimitive("frame_part"))))
            },
        )
    }
}
