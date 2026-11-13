package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Owned by agent A: visible to unrestricted peers and to peers paired with agent A. */
private val OWN = CanvasId("canvas-own")

/** Owned by agent B: invisible to a peer paired with agent A only. */
private val SECRET_CANVAS = CanvasId("canvas-other-agent-secret")

/** Not on the host at all. */
private val NOWHERE = CanvasId("canvas-nowhere")

/** Paired with agent A only. */
private val A_SCOPED = PEER.copy(agents = setOf(AGENT_A.value))

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
        // Candidates hold no record and reveal no winner; their denials are covered in CanvasMountCandidatesTest.
        CanvasMountRoute.entries.filter { it != CanvasMountRoute.CANDIDATES }.forEach { route ->
            val scopedOut = A_SCOPED.copy(conversations = setOf("conv-elsewhere"))
            // Resolve and bind are refused by the hidden winner; replace and default.set by the hidden proposal.
            val revealsWinner = route == CanvasMountRoute.RESOLVE || route == CanvasMountRoute.BIND
            val params = paramsFor(route, CONV_A, if (revealsWinner) OWN else SECRET_CANVAS)
            val absent = hosted().call(route, params, scopedOut)
            val existing = hosted().apply { seedBoth(SECRET_CANVAS) }.call(route, params, scopedOut)
            val hidden = hosted().apply { seedBoth(SECRET_CANVAS) }.call(route, params, A_SCOPED)
            assertEquals(DENIED_RESPONSE, absent.raw, "$route absent")
            assertEquals(absent.raw, existing.raw, "$route existing")
            assertEquals(absent.raw, hidden.raw, "$route hidden")
        }
    }

    @Test
    fun aCanvasMissingFromTheHostIsDeniedExactlyLikeOneTheCallerMayNotSee() = runBlocking {
        val harness = hosted()
        val missing = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NOWHERE), A_SCOPED)
        val invisible = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, SECRET_CANVAS), A_SCOPED)
        val missingForAll = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NOWHERE), PEER)
        assertEquals(DENIED_RESPONSE, missing.raw)
        assertEquals(missing.raw, invisible.raw)
        assertEquals(missing.raw, missingForAll.raw)
        assertNull(harness.store().readBinding(AGENT_A, CONV_A))
    }

    @Test
    fun anUnrestrictedPeerMountsACanvasCreatedByAnotherAgentButAnAgentScopedPeerCannot() = runBlocking {
        val harness = hosted()
        val params = bindingParams(AGENT_A, CONV_A, SECRET_CANVAS)
        assertEquals(DENIED_RESPONSE, harness.call(CanvasMountRoute.REPLACE, params, A_SCOPED).raw)
        val replaced = harness.call(CanvasMountRoute.REPLACE, params, PEER)
        assertEquals(CanvasMountWire.REPLACED to SECRET_CANVAS.value, replaced.status to replaced.canvas)
        assertEquals(DENIED_RESPONSE, harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), A_SCOPED).raw)
    }

    @Test
    fun aCanvasWithNoKnownOwnerIsVisibleOnlyToUnrestrictedPeers() = runBlocking {
        val harness = hosted()
        val appMade = CanvasId("canvas-made-by-an-app")
        harness.canvas(appMade, owner = null)
        val params = bindingParams(AGENT_A, CONV_A, appMade)
        assertEquals(DENIED_RESPONSE, harness.call(CanvasMountRoute.REPLACE, params, A_SCOPED).raw)
        assertEquals(CanvasMountWire.REPLACED, harness.call(CanvasMountRoute.REPLACE, params, PEER).status)
    }

    @Test
    fun theConversationsOwnCanvasIsAlwaysMountableToGoBack() = runBlocking {
        val harness = hosted()
        val own = CanvasId.forConversation(CONV_A)
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, OWN), A_SCOPED)
        // Not registered anywhere on the host: the conversation's own canvas is the natural state, not a lookup.
        val back = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, own), A_SCOPED)
        val resolved = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), A_SCOPED)
        assertEquals(CanvasMountWire.REPLACED to own.value, back.status to back.canvas)
        assertEquals(
            listOf(CanvasMountWire.FOUND, own.value, CanvasMountWire.SOURCE_BINDING),
            listOf(resolved.status, resolved.canvas, resolved.source),
        )
        // Another conversation's own canvas gets no such pass: it must exist and be visible.
        val foreign = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, CanvasId.forConversation(CONV_B)), A_SCOPED)
        assertEquals(DENIED_RESPONSE, foreign.raw)
    }

    @Test
    fun crossAgentAttemptsNeverReachTheRecordOrItsCanvas() = runBlocking {
        val harness = hosted()
        harness.store().bindIfAbsent(AGENT_B, CONV_B, SECRET_CANVAS)
        harness.store().bindIfAbsent(AGENT_A, CONV_A2, SECRET_CANVAS)
        val viaGuard = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_B, CONV_B), A_SCOPED)
        val viaVisibility = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A2), A_SCOPED)
        assertEquals(AgentScopeGuard.DENIED, viaGuard.error)
        assertEquals(DENIED_RESPONSE, viaVisibility.raw)
        assertFalse(harness.responses.any { SECRET_CANVAS.value in it })
    }

    /**
     * letta-mobile-wisqe.2 (agent-scoped peers reaching other agents' conversations by conversation id) does not reach
     * these routes: they take agent_id (checked by the router's agent scope guard) and the authority requires the
     * host's own conversation record to say the conversation belongs to that agent.
     */
    @Test
    fun anAgentScopedPeerCannotReachAnotherAgentsConversationByItsId() = runBlocking {
        val harness = hosted()
        harness.store().bindIfAbsent(AGENT_B, CONV_B, SECRET_CANVAS)
        listOf(CanvasMountRoute.RESOLVE, CanvasMountRoute.BIND, CanvasMountRoute.REPLACE).forEach { route ->
            val params = paramsFor(route, CONV_B, OWN)
            val ownAgent = harness.call(route, params, A_SCOPED)
            val otherAgent = harness.call(route, JsonObject(params + (CanvasMountWire.AGENT_ID to JsonPrimitive(AGENT_B.value))), A_SCOPED)
            assertEquals(DENIED_RESPONSE, ownAgent.raw, "$route with agent A")
            assertEquals(AgentScopeGuard.DENIED, otherAgent.error, "$route with agent B")
        }
        assertNull(harness.store().readBinding(AGENT_A, CONV_B))
        assertEquals(SECRET_CANVAS, harness.store().readBinding(AGENT_B, CONV_B))
    }

    @Test
    fun crossConversationAttemptsAreDeniedAndChangeNothing() = runBlocking {
        val harness = hosted()
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
        val harness = hosted()
        Spoof.entries.forEach { spoof ->
            val params = JsonObject(bindingParams(AGENT_A, CONV_A, OWN) + (spoof.key to JsonPrimitive(OTHER_PEER.endpoint)))
            val reply = harness.call(CanvasMountRoute.BIND, params, PEER)
            assertEquals("canvas.mount.invalid_input:params:unknown_field", reply.error, spoof.key)
        }
        assertNull(harness.store().readBinding(AGENT_A, CONV_A))
    }

    @Test
    fun aScopedPeerSmugglingASecondAgentIdIsRefusedByTheRouter() = runBlocking {
        val harness = hosted()
        val params = JsonObject(bindingParams(AGENT_B, CONV_B) + ("agentId" to JsonPrimitive(AGENT_A.value)))
        val reply = harness.call(CanvasMountRoute.RESOLVE, params, A_SCOPED)
        assertEquals(AgentScopeGuard.DENIED, reply.error)
    }

    @Test
    fun aConnectionWithoutVerifiedIdentityIsUnavailableWhateverItsParamsSay() = runBlocking {
        val harness = hosted()
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
        val harness = hosted()
        val unknownConversation = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, ConversationId("conv-missing"), OWN), PEER)
        val unknownAgent = harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AgentId("agent-missing"), OWN), PEER)
        assertEquals(DENIED_RESPONSE, unknownConversation.raw)
        assertEquals(DENIED_RESPONSE, unknownAgent.raw)
        assertNull(harness.store().readDefault(AgentId("agent-missing")))
    }

    @Test
    fun resolveFallsBackToTheAgentDefaultAndReportsItsSourceAndDurability() = runBlocking {
        val harness = hosted()
        assertEquals(CanvasMountWire.DEFAULT_SET, harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, OWN), PEER).status)
        val resolved = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        assertEquals(
            listOf(CanvasMountWire.FOUND, OWN.value, CanvasMountWire.SOURCE_DEFAULT),
            listOf(resolved.status, resolved.canvas, resolved.source),
        )
        assertEquals(CanvasMountDurability.ofHost().wire, resolved.durability)
    }

    @Test
    fun aBindingChangeIsPushedOnlyToViewersThatResolveWouldShowIt() = runBlocking {
        val harness = hosted()
        val allowed = FakeViewer(A_SCOPED.context())
        val otherAgent = FakeViewer(PEER.copy(agents = setOf(AGENT_B.value)).context())
        val otherConversation = FakeViewer(PEER.copy(conversations = setOf(CONV_A2.value)).context())
        val noCapability = FakeViewer(null)
        harness.records.viewers[CONV_A] = setOf(allowed, otherAgent, otherConversation, noCapability)
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, OWN), PEER)
        val frame = awaitFrame(allowed)
        val expected = """{"type":"canvas_mount_updated","agent_id":"agent-a","conversation_id":"conv-a",""" +
            """"canvas_id":"canvas-own","durability":"${CanvasMountDurability.ofHost().wire}"}"""
        assertEquals(expected, frame)
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, SECRET_CANVAS), PEER)
        delay(PUSH_SETTLE)
        assertEquals(1, allowed.frames.size, "the agent-A viewer must not learn agent B's canvas")
        listOf(otherAgent, otherConversation, noCapability).forEach { assertTrue(it.frames.isEmpty()) }
    }

    @Test
    fun readsDefaultsAndFailedWritesPushNothing() = runBlocking {
        val harness = hosted()
        val viewer = FakeViewer(PEER.context())
        harness.records.viewers[CONV_A] = setOf(viewer)
        harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, OWN), PEER)
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NOWHERE), PEER)
        delay(PUSH_SETTLE)
        assertTrue(viewer.frames.isEmpty())
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
    fun chatAccessRolesMayResolveAndSwitchButOnlyAdminsSetAgentDefaults() {
        listOf(CanvasMountWire.RESOLVE, CanvasMountWire.CANDIDATES).forEach {
            assertEquals(IrohPeerCapabilities.CANVAS_MOUNT_READ, IrohPeerCapabilities.forAdminMethod(it), it)
        }
        listOf(CanvasMountWire.BIND, CanvasMountWire.REPLACE).forEach {
            assertEquals(IrohPeerCapabilities.CANVAS_MOUNT_WRITE, IrohPeerCapabilities.forAdminMethod(it), it)
        }
        assertEquals(IrohPeerCapabilities.ADMIN_FULL, IrohPeerCapabilities.forAdminMethod(CanvasMountWire.SET_DEFAULT))
        val mountCapabilities = setOf(IrohPeerCapabilities.CANVAS_MOUNT_READ, IrohPeerCapabilities.CANVAS_MOUNT_WRITE)
        assertTrue(IrohPeerCapabilities.ALL.containsAll(mountCapabilities))
        assertTrue(IrohPeerCapabilities.roles(mountsEnabled = false).values.none { it.any(mountCapabilities::contains) })
        val chatRoles = IrohPeerCapabilities.roles(mountsEnabled = true).filterValues { IrohPeerCapabilities.CHAT_READ in it }
        assertEquals(setOf("chat", "desktop", "vibesync"), chatRoles.keys)
        chatRoles.forEach { (role, capabilities) -> assertTrue(capabilities.containsAll(mountCapabilities), role) }
        chatRoles.forEach { (role, capabilities) ->
            val required = IrohPeerCapabilities.forAdminMethod(CanvasMountWire.SET_DEFAULT)
            assertFalse(IrohPeerCapabilities.isAllowed(capabilities, required), role)
        }
    }

    /** A harness whose host knows [OWN] (agent A's) and [SECRET_CANVAS] (agent B's). */
    private suspend fun hosted(): MountHarness = MountHarness().apply {
        canvas(OWN, AGENT_A)
        canvas(SECRET_CANVAS, AGENT_B)
    }

    private suspend fun MountHarness.seedBoth(canvas: CanvasId) {
        store().bindIfAbsent(AGENT_A, CONV_A, canvas)
        store().setDefault(AGENT_A, canvas)
    }

    private suspend fun awaitFrame(viewer: FakeViewer): String = checkNotNull(
        withTimeoutOrNull(PUSH_WAIT) {
            while (viewer.frames.isEmpty()) delay(PUSH_POLL)
            viewer.frames.first()
        },
    ) { "no push reached the allowed viewer" }

    private fun routes(policy: IrohAuthPolicy): CanvasMountRoutes {
        val root = kotlin.io.path.createTempDirectory("canvas-mount-registry")
        return CanvasMountRoutes(
            policy,
            CanvasMountStoreLocation(root, TEST_HOST),
            CanvasMountSources(CanvasMountCatalog(InMemoryHostCanvasDirectory(), InMemoryCanvasRelayStore()), FakeDirectory()),
            TEST_CALL_SCOPE,
        )
    }

    private companion object {
        val PUSH_WAIT = 5.seconds
        val PUSH_POLL = 10.milliseconds
        val PUSH_SETTLE = 300.milliseconds
    }
}
