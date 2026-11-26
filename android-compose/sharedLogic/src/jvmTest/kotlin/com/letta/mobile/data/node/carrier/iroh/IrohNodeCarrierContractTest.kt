package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeCarrierContractSuite
import com.letta.mobile.data.node.carrier.NodeCarrierEndpoint
import com.letta.mobile.data.node.carrier.NodeCarrierHarness
import kotlin.test.BeforeTest
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue

/**
 * The node-carrier contract against the Iroh carrier, over two loopback Iroh endpoints: the suite's scripted node
 * behind [serveIrohNodePeer] on one, [IrohNodeCarrier] dialling from the other.
 *
 * Live QUIC, so opt-in like the other loopback Iroh suites (handshakes on shared CI runners time out now and then):
 *
 *   ./gradlew :sharedLogic:jvmTest -DrunIrohLiveE2E=true --tests 'com.letta.mobile.data.node.carrier.iroh.*'
 */
class IrohNodeCarrierContractTest : NodeCarrierContractSuite() {
    @BeforeTest
    fun requireOptIn() {
        assumeTrue("Live Iroh requires -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    override fun harness(): NodeCarrierHarness = NodeCarrierHarness { acceptor, scope ->
        val node = IrohLoopbackNode.start(acceptor, scope)
        val shell = bindLoopbackEndpoint()
        NodeCarrierEndpoint(
            carrier = IrohNodeCarrier(shell, scope, connectTimeout = DIAL_TIMEOUT),
            address = node.address(),
            unreachable = node.addressOfAnotherNode(),
            stop = {
                shell.release()
                node.stop()
            },
            // No sever: every way a test can end an Iroh connection is an application close (closing a connection or
            // the endpoint sends CONNECTION_CLOSE with a code), never a silent loss. A real loss needs the network cut
            // under QUIC until its idle timeout, which a loopback test cannot do quickly.
            sever = null,
        )
    }

    private companion object {
        val DIAL_TIMEOUT = 5.seconds
    }
}
