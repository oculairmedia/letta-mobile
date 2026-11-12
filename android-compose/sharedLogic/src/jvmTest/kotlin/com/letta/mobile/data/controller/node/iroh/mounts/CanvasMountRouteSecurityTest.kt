package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val OWN = CanvasId("canvas-own")
private val SECRET_CANVAS = CanvasId("canvas-other-agent-secret")

/** A spoofed field that names an actor, principal or host. */
private enum class Spoof(val key: String) {
    PRINCIPAL("principal"),
    ENDPOINT("endpoint_id"),
    AUTHENTICATED_ENDPOINT("authenticatedEndpointId"),
    HOST("host_id"),
    ACTOR("actor"),
    USER("user_id"),
    OWNER("owner"),
    CAMEL_AGENT("agentId"),
}

class CanvasMountRouteSecurityTest {
    @Test
    fun denialsAreByteIdenticalForExistingAbsentAndHiddenWinnerRecords() = runBlocking {
        CanvasMountRoute.entries.forEach { route ->
            val scopedOut = PEER.copy(conversations = setOf("conv-elsewhere"))
            // Resolve and bind are refused by the hidden winner; replace and default.set by the proposal.
            val revealsWinner = route == CanvasMountRoute.RESOLVE || route == CanvasMountRoute.BIND
            val level = if (revealsWinner) CanvasMountGrant.MOUNT else CanvasMountGrant.SEE
            val absent = MountHarness().call(route, paramsFor(route, CONV_A, OWN), scopedOut)
            val existing = MountHarness().apply { seedBoth(SECRET_CANVAS, level) }.call(route, paramsFor(route, CONV_A, OWN), scopedOut)
            val hidden = MountHarness().apply { seedBoth(SECRET_CANVAS, level) }.call(route, paramsFor(route, CONV_A, OWN), PEER)
            assertEquals(DENIED_RESPONSE, absent.raw, "$route absent")
            assertEquals(absent.raw, existing.raw, "$route existing")
            assertEquals(absent.raw, hidden.raw, "$route hidden winner")
        }
    }

    @Test
    fun crossAgentAttemptsNeverReachTheRecordOrItsCanvas() = runBlocking {
        val harness = MountHarness()
        harness.store().bindIfAbsent(AGENT_B, CONV_B, SECRET_CANVAS)
        harness.grant(PEER, OWN, CanvasMountGrant.MOUNT)
        val scoped = PEER.copy(agents = setOf(AGENT_A.value))
        val viaGuard = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_B, CONV_B), scoped)
        val viaGrant = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_B, CONV_B), PEER)
        val crossOwner = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_B, OWN), PEER)
        assertEquals(AgentScopeGuard.DENIED, viaGuard.error)
        assertEquals(DENIED_RESPONSE, viaGrant.raw)
        assertEquals(DENIED_RESPONSE, crossOwner.raw)
        assertNull(harness.store().readBinding(AGENT_A, CONV_B))
        assertFalse(harness.responses.any { SECRET_CANVAS.value in it })
    }

    @Test
    fun crossConversationAttemptsAreDeniedAndChangeNothing() = runBlocking {
        val harness = MountHarness()
        harness.grant(PEER, OWN, CanvasMountGrant.MOUNT)
        val viewer = PEER.copy(conversations = setOf(CONV_A.value))
        harness.store().bindIfAbsent(AGENT_A, CONV_A2, SECRET_CANVAS)
        for (route in listOf(CanvasMountRoute.RESOLVE, CanvasMountRoute.BIND, CanvasMountRoute.REPLACE)) {
            assertEquals(DENIED_RESPONSE, harness.call(route, paramsFor(route, CONV_A2, OWN), viewer).raw, "$route")
        }
        assertEquals(SECRET_CANVAS, harness.store().readBinding(AGENT_A, CONV_A2))
        assertEquals(CanvasMountWire.BOUND, harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, OWN), viewer).status)
    }

    @Test
    fun spoofedPrincipalHostAndActorFieldsAreRefusedBeforeAnyStorage() = runBlocking {
        val harness = MountHarness()
        harness.grant(OTHER_PEER, OWN, CanvasMountGrant.MOUNT)
        Spoof.entries.forEach { spoof ->
            val params = JsonObject(bindingParams(AGENT_A, CONV_A, OWN) + (spoof.key to JsonPrimitive(OTHER_PEER.endpoint)))
            val reply = harness.call(CanvasMountRoute.BIND, params, PEER)
            assertEquals("canvas.mount.invalid_input:params:unknown_field", reply.error, spoof.key)
        }
        assertNull(harness.store().readBinding(AGENT_A, CONV_A))
        // The grant belongs to OTHER_PEER; naming it anywhere in params never lends it to PEER.
        assertEquals(DENIED_RESPONSE, harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, OWN), PEER).raw)
    }

    @Test
    fun aScopedPeerSmugglingASecondAgentIdIsRefusedByTheRouter() = runBlocking {
        val harness = MountHarness()
        val params = JsonObject(bindingParams(AGENT_B, CONV_B) + ("agentId" to JsonPrimitive(AGENT_A.value)))
        val reply = harness.call(CanvasMountRoute.RESOLVE, params, PEER.copy(agents = setOf(AGENT_A.value)))
        assertEquals(AgentScopeGuard.DENIED, reply.error)
    }

    @Test
    fun aConnectionWithoutVerifiedIdentityIsUnavailableWhateverItsParamsSay() = runBlocking {
        val harness = MountHarness()
        harness.grant(PEER, OWN, CanvasMountGrant.MOUNT)
        val contexts = listOf(
            AdminRpcRequestContext(authenticated = false, authenticatedEndpointId = PEER.endpoint),
            AdminRpcRequestContext.Authenticated,
            PEER.copy(endpoint = "not-a-64-hex-endpoint").context(),
            PEER.copy(endpoint = PEER.endpoint.uppercase()).context(),
        )
        contexts.forEach { context ->
            val reply = harness.call(CanvasMountWire.BIND, bindingParams(AGENT_A, CONV_A, OWN), context)
            assertEquals(CanvasMountWire.UNAVAILABLE to CanvasMountWire.REASON_IDENTITY, reply.status to reply.reason, "$context")
        }
        assertNull(harness.store().readBinding(AGENT_A, CONV_A))
    }

    @Test
    fun bindNeedsAnExistingConversationOfTheAgentAndDefaultsAnExistingAgent() = runBlocking {
        val harness = MountHarness()
        harness.grant(PEER, OWN, CanvasMountGrant.MOUNT)
        val unknownConversation = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, ConversationId("conv-missing"), OWN), PEER)
        val unknownAgent = harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AgentId("agent-missing"), OWN), PEER)
        assertEquals(DENIED_RESPONSE, unknownConversation.raw)
        assertEquals(DENIED_RESPONSE, unknownAgent.raw)
        assertNull(harness.store().readDefault(AgentId("agent-missing")))
    }

    @Test
    fun resolveFallsBackToTheAgentDefaultAndReportsItsSourceAndDurability() = runBlocking {
        val harness = MountHarness()
        harness.grant(PEER, OWN, CanvasMountGrant.MOUNT)
        assertEquals(CanvasMountWire.DEFAULT_SET, harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, OWN), PEER).status)
        val resolved = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        assertEquals(
            listOf(CanvasMountWire.FOUND, OWN.value, CanvasMountWire.SOURCE_DEFAULT),
            listOf(resolved.status, resolved.canvas, resolved.source),
        )
        assertEquals(CanvasMountDurability.ofHost().wire, resolved.durability)
    }

    @Test
    fun theAnonymousTestPolicyRegistersAndAdvertisesNothing() {
        val insecure = MountHarness(options = HarnessOptions(policy = IrohAuthPolicy.InsecureAnonymousForTestOnly))
        assertTrue(insecure.router.registeredMethods.isEmpty())
        assertTrue(insecure.router.featureCapabilities.isEmpty())
        val secure = listOf(
            IrohAuthPolicy.PairedPeersOnly,
            IrohAuthPolicy.BearerToken("t".repeat(IrohAuthPolicy.MIN_TOKEN_LENGTH)),
            IrohAuthPolicy.PeerAllowlist(setOf(PEER.endpoint)),
        )
        secure.forEach { policy ->
            val router = MountHarness(options = HarnessOptions(policy = policy)).router
            assertEquals(CanvasMountWire.METHODS, router.registeredMethods, "$policy")
            assertEquals(setOf(CanvasMountWire.CAPABILITY), router.featureCapabilities, "$policy")
        }
    }

    @Test
    fun theRegistryRegistersTheRoutesOnlyThroughTheHostServiceAndOnlyUnderASecurePolicy() {
        val without = AdminRpcRegistry.buildRouter(services = AdminRpcHostServices())
        val insecureRoutes = routes(IrohAuthPolicy.InsecureAnonymousForTestOnly)
        val insecure = AdminRpcRegistry.buildRouter(services = AdminRpcHostServices(canvasMountRoutes = insecureRoutes))
        val secureRoutes = routes(IrohAuthPolicy.PairedPeersOnly)
        val secure = AdminRpcRegistry.buildRouter(services = AdminRpcHostServices(canvasMountRoutes = secureRoutes))
        listOf(without, insecure).forEach { router ->
            assertTrue(router.registeredMethods.none { it in CanvasMountWire.METHODS })
            assertFalse(CanvasMountWire.CAPABILITY in router.featureCapabilities)
        }
        assertTrue(secure.registeredMethods.containsAll(CanvasMountWire.METHODS))
        assertTrue(CanvasMountWire.CAPABILITY in secure.featureCapabilities)
    }

    @Test
    fun routesRequireTheirOwnCapabilitiesWhichNoDefaultRoleGrants() {
        assertEquals(IrohPeerCapabilities.CANVAS_MOUNT_READ, IrohPeerCapabilities.forAdminMethod(CanvasMountWire.RESOLVE))
        (CanvasMountWire.METHODS - CanvasMountWire.RESOLVE).forEach {
            assertEquals(IrohPeerCapabilities.CANVAS_MOUNT_WRITE, IrohPeerCapabilities.forAdminMethod(it), it)
        }
        val mountCapabilities = setOf(IrohPeerCapabilities.CANVAS_MOUNT_READ, IrohPeerCapabilities.CANVAS_MOUNT_WRITE)
        assertTrue(IrohPeerCapabilities.ALL.containsAll(mountCapabilities))
        IrohPeerCapabilities.ROLES.filterKeys { it != "admin" }.forEach { (role, capabilities) ->
            assertTrue(capabilities.none { it in mountCapabilities }, role)
        }
    }

    private suspend fun MountHarness.seedBoth(canvas: CanvasId, level: CanvasMountGrant) {
        store().bindIfAbsent(AGENT_A, CONV_A, canvas)
        store().setDefault(AGENT_A, canvas)
        grant(PEER, OWN, level)
    }

    private fun routes(policy: IrohAuthPolicy): CanvasMountRoutes {
        val root = kotlin.io.path.createTempDirectory("canvas-mount-registry")
        return CanvasMountRoutes(
            policy,
            CanvasMountStoreLocation(root, TEST_HOST),
            CanvasMountSources(CanvasMountGrants(root.resolve("canvas-grants.json")), FakeDirectory()),
            TEST_CALL_SCOPE,
        )
    }
}
