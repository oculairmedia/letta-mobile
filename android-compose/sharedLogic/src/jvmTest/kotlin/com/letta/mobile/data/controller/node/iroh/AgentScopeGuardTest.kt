package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun agentIdIsFoundAtTopLevelOrUnderRuntime() {
        assertEquals("a1", AgentScopeGuard.agentIdOf(obj("agent_id" to "a1")))
        assertEquals("a2", AgentScopeGuard.agentIdOf(buildJsonObject { put("runtime", obj("agent_id" to "a2")) }))
        assertEquals(null, AgentScopeGuard.agentIdOf(obj("conversation_id" to "c")))
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
