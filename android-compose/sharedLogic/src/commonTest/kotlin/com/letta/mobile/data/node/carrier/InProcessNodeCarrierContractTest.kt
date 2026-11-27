package com.letta.mobile.data.node.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The contract suite against the in-process carrier, plus what is particular to it: identities and a stopped node. */
class InProcessNodeCarrierContractTest : NodeCarrierContractSuite() {
    override fun harness(): NodeCarrierHarness = NodeCarrierHarness { acceptor, scope ->
        // The node runs in a scope of its own, so stopping it is the in-process carrier's transport loss: there is no
        // transport under the link but the node itself.
        val node = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val carrier = InProcessNodeCarrier(NAME, NODE, acceptor, node)
        NodeCarrierEndpoint(
            carrier = carrier,
            address = carrier.address,
            unreachable = NodeAddress(InProcessNodeCarrier.SCHEME + "nowhere"),
            stop = {},
            sever = { node.cancel() },
        )
    }

    @Test
    fun theShellSeesTheNodesIdentityAndTheNodeSeesTheLocalShell() = runTest {
        var peer: NodePeerIdentity? = null
        val carrier = InProcessNodeCarrier(NAME, NODE, { link -> peer = link.peer }, backgroundScope)
        val link = carrier.dial(carrier.address)
        assertEquals(NODE, link.node)
        assertEquals(NodeLinkClosure.NORMAL, link.closure.first { it != null }, "a node that returns closes the link normally")
        assertEquals(NodePeerIdentity.LOCAL_SHELL, peer)
    }

    @Test
    fun aNodeThatFailsLosesTheLink() = runTest {
        val carrier = InProcessNodeCarrier(NAME, NODE, { error("node bug") }, backgroundScope)
        val link = carrier.dial(carrier.address)
        assertEquals(NodeLinkClosure.LOST, link.closure.first { it != null })
    }

    @Test
    fun aNodeStoppedBeforeItServesLosesTheLink() = runTest {
        // The serve is dispatched, not run, so the node stops before it starts: its own close never runs.
        val node = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val carrier = InProcessNodeCarrier(NAME, NODE, { }, node)
        val link = carrier.dial(carrier.address)
        node.cancel()
        assertEquals(NodeLinkClosure.LOST, link.closure.first { it != null })
    }

    @Test
    fun aFrameCrossesAsASnapshotNotTheSendersTree() = runTest {
        val received = CompletableDeferred<NodeFrame>()
        val carrier = InProcessNodeCarrier(NAME, NODE, { link -> received.complete(link.control.first()) }, backgroundScope)
        val fields = mutableMapOf<String, JsonElement>("type" to JsonPrimitive("sync"))
        val link = carrier.dial(carrier.address)
        link.sendControl(NodeFrame(JsonObject(fields)))
        fields["type"] = JsonPrimitive("changed")
        assertEquals("sync", received.await().type, "a later change by the sender never reaches the node")
    }

    @Test
    fun aStoppedNodeIsUnreachable() = runTest {
        val stopped = CoroutineScope(Job()).apply { cancel() }
        val carrier = InProcessNodeCarrier(NAME, NODE, { }, stopped)
        val failure = runCatching { carrier.dial(carrier.address) }.exceptionOrNull()
        assertEquals(true, (failure as? NodeCarrierException)?.failure is NodeCarrierFailure.Unreachable, "$failure")
    }

    @Test
    fun completedCallLanesLeaveTheLink() = runTest {
        val carrier = InProcessNodeCarrier(NAME, NODE, { link -> link.calls.collect { lane -> answerOnce(lane) } }, backgroundScope)
        val link = carrier.dial(carrier.address)
        repeat(CALLS) { index ->
            val lane = link.openCall(callFrame("call-$index"))
            lane.finishSending()
            // The node's end finished once its answer's lane completes, so the lane has ended on both sides.
            assertEquals(listOf("call-$index"), lane.incoming.toList().map { it.requestId }, "each call is answered once")
        }
        assertEquals(0, carrier.openCallLanes.value, "a call both ends finished leaves its link")
        val open = link.openCall(callFrame("still-open"))
        assertEquals(listOf("still-open"), open.incoming.toList().map { it.requestId }, "the node answered and finished")
        assertEquals(1, carrier.openCallLanes.value, "a call the shell has not finished stays")
        open.finishSending()
        assertEquals(0, carrier.openCallLanes.value, "the shell's finish ends it")
        link.close()
    }

    @Test
    fun aClosedLinkForgetsItsOpenCallLanes() = runTest {
        val carrier = InProcessNodeCarrier(NAME, NODE, { link -> link.calls.collect { } }, backgroundScope)
        val link = carrier.dial(carrier.address)
        repeat(3) { link.openCall(callFrame("open-$it")) }
        assertEquals(3, carrier.openCallLanes.value)
        link.close()
        assertEquals(0, carrier.openCallLanes.value, "the link's close ends every lane it still had")
    }

    private suspend fun answerOnce(lane: NodeCallLane) {
        val request = lane.incoming.first()
        lane.send(request)
        lane.finishSending()
    }

    private fun callFrame(requestId: String) = NodeFrame(
        JsonObject(mapOf("type" to JsonPrimitive("admin_rpc"), "request_id" to JsonPrimitive(requestId))),
    )

    private companion object {
        const val CALLS = 200

        const val NAME = "contract-node"
        val NODE = NodePeerIdentity("in-process-node")
    }
}
