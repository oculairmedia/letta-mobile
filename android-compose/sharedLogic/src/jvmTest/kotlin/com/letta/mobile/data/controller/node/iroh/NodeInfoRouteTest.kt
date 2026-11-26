package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.node.info.NodeCapability
import com.letta.mobile.data.node.info.NodeFacts
import com.letta.mobile.data.node.info.NodeInfo
import com.letta.mobile.data.node.info.NodeInfoWire
import com.letta.mobile.data.node.info.RuntimePatchBootLineObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NodeInfoRouteTest {
    @Test
    fun everyNodeAnswersNodeInfoWithItsFactsAndThePatchSetItsAppServerReported() = runTest {
        val patches = RuntimePatchBootLineObserver()
        val router = AdminRpcRegistry.buildRouter(
            services = AdminRpcHostServices(
                nodeFacts = NodeFacts("linux", "1.4.0", setOf(NodeCapability.CATALOG, NodeCapability.ALWAYS_ON), patches),
            ),
        )
        patches.observe("[letta-code-patch] lcp-patches applied=36 skipped=0")

        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r1", NodeInfoWire.METHOD, null))).jsonObject

        assertEquals("true", response.getValue("success").jsonPrimitive.content)
        val info = Json.decodeFromJsonElement(NodeInfo.serializer(), response.getValue("result"))
        assertEquals("linux", info.os)
        assertEquals("1.4.0", info.appVersion)
        assertEquals(NodeInfoWire.UNKNOWN, info.lettaCodeVersion, "no App Server is wired")
        assertEquals(listOf("catalog", "always-on"), info.capabilities)
        assertEquals(36, info.runtimePatches.applied)
    }

    @Test
    fun anyAuthenticatedPeerMayReadItWhateverItsCapabilities() {
        val gate = IrohPeerCapabilities.forAdminMethod(NodeInfoWire.METHOD)

        assertEquals(IrohPeerCapabilities.PEER_AUTHENTICATED, gate)
        assertFalse(gate in IrohPeerCapabilities.ALL, "the gate is never grantable")
        assertFalse(IrohPeerCapabilities.isAllowed(setOf(IrohPeerCapabilities.ADMIN_FULL), gate), "a capability never satisfies it")

        val policy = IrohAuthPolicy.PairedPeersOnly
        val pairing = IrohPairingService(InMemoryPairedPeerStore())
        val narrow = PeerSessionAuth(policy, IrohBearerAuthVerifier(policy), pairing, "a".repeat(64))
        val stranger = PeerSessionAuth(policy, IrohBearerAuthVerifier(policy), pairing, "b".repeat(64))
        val invite = pairing.createInvite(
            IrohPairingService.InviteRequest("producer", capabilities = setOf(IrohPeerCapabilities.WORKACTIVITY_REPORT)),
        )
        assertTrue(narrow.authenticate("invite:${invite.secret}", "producer") is PeerSessionAuth.Decision.Accepted)

        assertTrue(narrow.allowsAdminCapability(gate), "a paired peer without chat.read still reads node.info")
        assertFalse(narrow.allowsAdminCapability(IrohPeerCapabilities.forAdminMethod("health.check")))
        assertFalse(stranger.allowsAdminCapability(gate), "an unauthenticated peer never does")
    }
}
