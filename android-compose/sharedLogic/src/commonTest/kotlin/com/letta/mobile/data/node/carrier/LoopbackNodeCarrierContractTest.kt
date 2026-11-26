package com.letta.mobile.data.node.carrier

/** The contract suite against the reference loopback carrier: proves the suite and the fixture themselves. */
class LoopbackNodeCarrierContractTest : NodeCarrierContractSuite() {
    override fun harness(): NodeCarrierHarness = NodeCarrierHarness { acceptor, scope ->
        val carrier = LoopbackNodeCarrier(acceptor, scope)
        NodeCarrierEndpoint(
            carrier = carrier,
            address = LoopbackNodeCarrier.ADDRESS,
            unreachable = LoopbackNodeCarrier.NOWHERE,
            stop = {},
            sever = carrier::sever,
        )
    }
}
