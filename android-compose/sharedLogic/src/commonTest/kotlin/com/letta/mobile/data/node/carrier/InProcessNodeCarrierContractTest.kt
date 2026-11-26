package com.letta.mobile.data.node.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

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
    fun aStoppedNodeIsUnreachable() = runTest {
        val stopped = CoroutineScope(Job()).apply { cancel() }
        val carrier = InProcessNodeCarrier(NAME, NODE, { }, stopped)
        val failure = runCatching { carrier.dial(carrier.address) }.exceptionOrNull()
        assertEquals(true, (failure as? NodeCarrierException)?.failure is NodeCarrierFailure.Unreachable, "$failure")
    }

    private companion object {
        const val NAME = "contract-node"
        val NODE = NodePeerIdentity("in-process-node")
    }
}
