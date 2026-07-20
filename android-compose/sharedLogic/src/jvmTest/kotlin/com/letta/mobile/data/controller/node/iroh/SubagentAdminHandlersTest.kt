package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentAdminHandlersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `canonical contract includes scoped subagent methods`() {
        assertTrue("subagent.list" in AdminRpcRegistry.canonicalMethods)
        assertTrue("subagent.todos" in AdminRpcRegistry.canonicalMethods)
    }

    @Test
    fun `list requires conversation scope and filters mismatched conversation and agent`() = runTest {
        val source = RecordingSource(
            entries = listOf(
                entry("wanted", "conv-a", "agent-a"),
                entry("other-conversation", "conv-b", "agent-a"),
                entry("other-agent", "conv-a", "agent-b"),
            ),
        )
        val router = router(source)
        val response = dispatch(
            router,
            "subagent.list",
            buildJsonObject {
                put("conversation_id", "conv-a")
                put("agent_id", "agent-a")
                put("all", true)
            },
            AdminRpcRequestContext(true, setOf("conv-a")),
        )

        assertTrue(response["success"]!!.jsonPrimitive.content.toBoolean())
        val subagents = response["result"]!!.jsonObject["subagents"]!!.jsonArray
        assertEquals(listOf("wanted"), subagents.map { it.jsonObject["toolCallId"]!!.jsonPrimitive.content })
        assertEquals(listOf(Triple<String, Boolean, String?>("conv-a", true, null)), source.calls)
    }

    @Test
    fun `list denies missing unauthorized and unauthenticated scope without reading source`() = runTest {
        val source = RecordingSource()
        val router = router(source)

        val missing = dispatch(router, "subagent.list", buildJsonObject {}, AdminRpcRequestContext.Authenticated)
        assertFalse(missing["success"]!!.jsonPrimitive.content.toBoolean())
        val forbidden = dispatch(
            router, "subagent.list", params("conv-b"), AdminRpcRequestContext(true, setOf("conv-a")),
        )
        assertEquals("forbidden", forbidden["error"]!!.jsonPrimitive.content)
        val unauthenticated = dispatch(
            router, "subagent.list", params("conv-a"), AdminRpcRequestContext.Unauthenticated,
        )
        assertEquals("unauthorized", unauthenticated["error"]!!.jsonPrimitive.content)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `todos maps authoritative snapshot and rejects cross-scope entry`() = runTest {
        val todo = SubagentTodo("Ship parity", "in_progress", "Shipping parity")
        val source = RecordingSource(
            todosSnapshot = SubagentTodosSnapshot(entry("tc-1", "conv-a", "agent-a"), listOf(todo), true),
        )
        val router = router(source)
        val params = buildJsonObject {
            put("conversation_id", "conv-a")
            put("tool_call_id", "tc-1")
        }
        val response = dispatch(router, "subagent.todos", params, AdminRpcRequestContext(true, setOf("conv-a")))
        val result = response["result"]!!.jsonObject

        assertTrue(result["found"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["todos_found"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Ship parity", result["todos"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(listOf(Triple<String, Boolean, String?>("conv-a", false, "tc-1")), source.calls)

        source.todosSnapshot = SubagentTodosSnapshot(entry("tc-1", "conv-b", "agent-a"), listOf(todo), true)
        val isolated = dispatch(router, "subagent.todos", params, AdminRpcRequestContext(true, setOf("conv-a")))
        assertFalse(isolated["result"]!!.jsonObject["found"]!!.jsonPrimitive.content.toBoolean())
    }

    private fun router(source: SubagentRegistrySource): AdminRpcRouter = AdminRpcRouter().also {
        SubagentAdminHandlers.register(it, source)
    }

    private suspend fun dispatch(
        router: AdminRpcRouter,
        method: String,
        params: kotlinx.serialization.json.JsonObject,
        context: AdminRpcRequestContext,
    ) = json.parseToJsonElement(router.dispatch("request", method, params, context)).jsonObject

    private fun params(conversationId: String) = buildJsonObject { put("conversation_id", conversationId) }

    private fun entry(toolCallId: String, conversationId: String, agentId: String) = SubagentEntry(
        toolCallId = toolCallId,
        status = SubagentStatus.RUNNING,
        parentConversationId = conversationId,
        parentAgentId = agentId,
    )

    private class RecordingSource(
        private val entries: List<SubagentEntry> = emptyList(),
        var todosSnapshot: SubagentTodosSnapshot? = null,
    ) : SubagentRegistrySource {
        val calls = mutableListOf<Triple<String, Boolean, String?>>()

        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> {
            calls += Triple(conversationId, includeTerminal, null)
            return entries
        }

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
            calls += Triple(conversationId, false, toolCallId)
            return todosSnapshot
        }
    }
}
