package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Agent-scoped paired devices (letta-mobile-dpv7m.1) — enforced centrally in the admin router. */
class AgentScopeGuardTest {
    private val scope = setOf("agent-mine")

    private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun frame(topLevel: String?, runtime: String?): JsonObject = buildJsonObject {
        topLevel?.let { put("agent_id", it) }
        runtime?.let { put("runtime", obj("agent_id" to it, "conversation_id" to "c")) }
    }

    /** letta-mobile-wisqe.2: a run is addressed only by its id, so its recorded agent decides whether a scoped peer sees it. */
    @Test
    fun runAndStepIdsCannotBypassTheOwningAgentScope() = runTest {
        val root = kotlin.io.path.createTempDirectory("run-owner-scope").toFile()
        try {
            for ((id, owner) in mapOf("owned" to "agent-mine", "foreign" to "agent-other")) {
                val directory = root.resolve("runs/$id").apply { mkdirs() }
                directory.resolve("run.json").writeText(obj("id" to id, "agent_id" to owner).toString())
            }
            val router = AdminRpcRouter()
            RunAdminHandlers.register(router, LocalBackendAdminStore(root))
            val chatPeer = AdminRpcRequestContext(
                authenticated = true, authorizedAgentIds = scope, grantedCapabilities = IrohPeerCapabilities.CHAT_ROLE,
            )
            for (method in listOf("run.get", "step.list")) {
                for ((id, allowed) in mapOf("owned" to true, "foreign" to false, "missing" to false)) {
                    val reply = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, obj("run_id" to id), chatPeer)))
                    assertEquals(allowed, reply.jsonObject.getValue("success").jsonPrimitive.boolean, "$method/$id: $reply")
                    if (!allowed) assertEquals(AgentScopeGuard.DENIED, reply.jsonObject["error"]?.jsonPrimitive?.content)
                }
                // The unscoped owner/desktop session is unaffected.
                val unscoped = router.dispatch(AdminRpcInvocation("r", method, obj("run_id" to "foreign")))
                assertTrue(Json.parseToJsonElement(unscoped).jsonObject.getValue("success").jsonPrimitive.boolean, unscoped)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** The real conversation routes, owned per [owners], as the default chat role's agent-scoped peer sees them. */
    private fun conversationRouter(owners: Map<String, String>, extra: List<String> = emptyList()): AdminRpcRouter =
        AdminRpcRouter().also { router ->
            ConversationAdminHandlers.register(
                router,
                NativeReadTiers(nativeClient = FakeNativeClient(extraConversationIds = extra, conversationAgentIds = owners)),
            )
        }

    private val chatPeer = AdminRpcRequestContext(
        authenticated = true,
        // What IrohNodeConnection derives for the default invite role: conversation.manage is unrestricted in content.
        authorizedConversationIds = IrohPeerCapabilities.conversationScope(IrohPeerCapabilities.CHAT_ROLE, null),
        authorizedAgentIds = scope,
        grantedCapabilities = IrohPeerCapabilities.CHAT_ROLE,
    )

    private suspend fun AdminRpcRouter.call(method: String, params: JsonObject, context: AdminRpcRequestContext = chatPeer) =
        Json.parseToJsonElement(dispatch(AdminRpcInvocation("r", method, params, context))).jsonObject

    private val JsonObject.ok: Boolean get() = getValue("success").jsonPrimitive.boolean

    private val JsonObject.error: String? get() = this["error"]?.jsonPrimitive?.content

    /**
     * letta-mobile-wisqe.2 F1: the default chat role holds conversation.manage, so its conversation content is
     * unrestricted; before the fix nothing resolved the owner of a conversation named only by id, so an agent-scoped
     * peer read and changed another agent's conversations by omitting agent_id.
     */
    @Test
    fun aScopedChatPeerCannotReachAnotherAgentsConversationByOmittingAgentId() = runTest {
        val native = FakeNativeClient(conversationAgentIds = mapOf("foreign" to "agent-other", "owned" to "agent-mine"))
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = native)) }
        val methods = listOf(
            "message.list" to obj("conversation_id" to "foreign"),
            "message.get" to obj("conversation_id" to "foreign", "message_id" to "m"),
            "tool_return.get" to obj("conversation_id" to "foreign", "message_id" to "m"),
            "conversation.get" to obj("conversation_id" to "foreign"),
            "conversation.update" to obj("conversation_id" to "foreign", "summary" to "renamed"),
            "conversation.archive" to obj("conversation_id" to "foreign"),
            "conversation.delete" to obj("conversation_id" to "foreign"),
        )
        for ((method, params) in methods) {
            native.calls.clear()
            val reply = router.call(method, params)
            assertFalse(reply.ok, "$method: $reply")
            assertEquals(AgentScopeGuard.DENIED, reply.error, method)
            assertTrue(native.calls.all { it == "conversation_retrieve" }, "$method must not reach the App Server: ${native.calls}")
        }
        assertTrue(router.call("message.list", obj("conversation_id" to "owned")).ok)
        assertTrue(router.call("conversation.get", obj("conversation_id" to "owned")).ok)
    }

    @Test
    fun anUnknownOrUnresolvableOwnerDeniesAScopedPeerButNotAnUnscopedOne() = runTest {
        val router = conversationRouter(mapOf("owned" to "agent-mine"))
        for (id in listOf("unknown-owner", "conv-default-agent-other")) {
            assertEquals(AgentScopeGuard.DENIED, router.call("message.list", obj("conversation_id" to id)).error, id)
        }
        val failing = AdminRpcRouter().apply {
            conversationOwner = { error("records down") }
            registerScoped("message.list") { _, _ -> JsonPrimitive("leaked") }
        }
        assertEquals(AgentScopeGuard.DENIED, failing.call("message.list", obj("conversation_id" to "owned")).error)
        // Unscoped owner/desktop sessions never consult the owner.
        assertTrue(failing.call("message.list", obj("conversation_id" to "owned"), AdminRpcRequestContext.Authenticated).ok)
        assertTrue(router.call("message.list", obj("conversation_id" to "conv-default-agent-mine")).ok)
    }

    @Test
    fun aBareDefaultConversationBelongsToTheOneAgentTheFrameNames() = runTest {
        val router = conversationRouter(emptyMap())
        assertEquals(AgentScopeGuard.DENIED, router.call("message.list", obj("conversation_id" to "default")).error)
        val other = router.call("message.list", obj("conversation_id" to "default", "agent_id" to "agent-other"))
        assertEquals(AgentScopeGuard.DENIED, other.error)
        assertTrue(router.call("message.list", obj("conversation_id" to "default", "agent_id" to "agent-mine")).ok)
    }

    @Test
    fun aRuntimeScopedConversationIsResolvedToo() = runTest {
        val router = conversationRouter(mapOf("foreign" to "agent-other"))
        val runtime = buildJsonObject { put("runtime", obj("agent_id" to "agent-mine", "conversation_id" to "foreign")) }
        assertFalse(router.conversationsInScope("input", runtime, chatPeer))
        assertTrue(router.conversationsInScope("input", runtime, AdminRpcRequestContext.Authenticated))
    }

    @Test
    fun aScopedChatPeerListsOnlyItsAgentsConversations() = runTest {
        val owners = mapOf("conv-1" to "agent-mine", "foreign" to "agent-other")
        val router = conversationRouter(owners, extra = listOf("foreign", "ownerless"))
        val rows = router.call("conversation.list", JsonObject(emptyMap())).getValue("result").jsonArray
        assertEquals(listOf("conv-1"), rows.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val all = router.call("conversation.list", JsonObject(emptyMap()), AdminRpcRequestContext.Authenticated)
            .getValue("result").jsonArray
        assertEquals(3, all.size, "unscoped sessions keep the full list")
    }

    @Test
    fun agentIdsAreCollectedFromTopLevelAndRuntime() {
        assertEquals(setOf("a1"), AgentScopeGuard.agentIdsOf(obj("agent_id" to "a1")))
        assertEquals(setOf("a2"), AgentScopeGuard.agentIdsOf(frame(topLevel = null, runtime = "a2")))
        assertEquals(setOf("a1", "a2"), AgentScopeGuard.agentIdsOf(frame(topLevel = "a1", runtime = "a2")))
        assertEquals(setOf("a1", "a3"), AgentScopeGuard.agentIdsOf(obj("agent_id" to "a1", "agentId" to "a3")))
        assertEquals(emptySet(), AgentScopeGuard.agentIdsOf(obj("conversation_id" to "c")))
    }

    @Test
    fun inScopeTopLevelCannotSmuggleOutOfScopeRuntimeAgent() {
        assertFalse(AgentScopeGuard.allowsParams(scope, frame(topLevel = "agent-mine", runtime = "agent-other")))
        assertFalse(AgentScopeGuard.allowsParams(scope, frame(topLevel = "agent-other", runtime = "agent-mine")))
    }

    @Test
    fun matchingInScopeTopLevelAndRuntimeAgentsAreAllowed() {
        assertTrue(AgentScopeGuard.allowsParams(scope, frame(topLevel = "agent-mine", runtime = "agent-mine")))
    }

    @Test
    fun disagreeingAgentIdsAreDeniedEvenWhenBothAreInScope() {
        val wide = setOf("agent-mine", "agent-also-mine")
        assertFalse(AgentScopeGuard.allowsParams(wide, frame(topLevel = "agent-mine", runtime = "agent-also-mine")))
        assertTrue(AgentScopeGuard.allowsParams(null, frame(topLevel = "agent-mine", runtime = "agent-other")))
    }

    @Test
    fun runtimeOnlyAgentIsChecked() {
        assertFalse(AgentScopeGuard.allowsParams(scope, frame(topLevel = null, runtime = "agent-other")))
        assertTrue(AgentScopeGuard.allowsParams(scope, frame(topLevel = null, runtime = "agent-mine")))
    }

    @Test
    fun frameNamingNoAgentPasses() {
        assertTrue(AgentScopeGuard.allowsParams(scope, frame(topLevel = null, runtime = null)))
        assertTrue(AgentScopeGuard.allowsParams(scope, obj("conversation_id" to "c")))
    }

    @Test
    fun outOfScopeParamsAreRefusedAndUnscopedPeersPass() {
        assertFalse(AgentScopeGuard.allowsParams(scope, obj("agent_id" to "agent-other")))
        assertTrue(AgentScopeGuard.allowsParams(scope, obj("agent_id" to "agent-mine")))
        assertTrue(AgentScopeGuard.allowsParams(scope, null))
        assertTrue(AgentScopeGuard.allowsParams(null, obj("agent_id" to "agent-other")))
    }

    @Test
    fun listingsDropOtherAgentsRows() {
        val agents = buildJsonArray {
            add(obj("id" to "agent-mine"))
            add(obj("id" to "agent-other"))
        }
        assertEquals(1, (AgentScopeGuard.filterResult(scope, "agent.list", agents) as JsonArray).size)

        val conversations = buildJsonObject {
            put(
                "conversations",
                buildJsonArray {
                    add(obj("id" to "c1", "agent_id" to "agent-mine"))
                    add(obj("id" to "c2", "agent_id" to "agent-other"))
                },
            )
        }
        val filtered = AgentScopeGuard.filterResult(scope, "conversation.list", conversations).jsonObject
        assertEquals(listOf("c1"), filtered["conversations"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        // Unscoped peers see everything.
        assertEquals(agents, AgentScopeGuard.filterResult(null, "agent.list", agents))
    }

    @Test
    fun routerEnforcesScopeBeforeTheHandlerRuns() = runTest {
        var handlerRan = false
        val router = AdminRpcRouter().also { r ->
            r.register("agent.get") { _ ->
                handlerRan = true
                obj("id" to "agent-other")
            }
            r.register("agent.list") { _ -> buildJsonArray { add(obj("id" to "agent-mine")); add(obj("id" to "agent-other")) } }
        }
        val scoped = AdminRpcRequestContext(authenticated = true, authorizedAgentIds = scope)

        val denied = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("1", "agent.get", obj("agent_id" to "agent-other"), scoped)),
        ).jsonObject
        assertFalse(denied["success"]!!.jsonPrimitive.boolean)
        assertFalse(handlerRan, "a scope denial must have no side effects")

        val listed = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("2", "agent.list", null, scoped))).jsonObject
        assertEquals(1, listed["result"]!!.jsonArray.size)
    }
}
