package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private val scopedA = AdminRpcRequestContext(
        authenticated = true,
        authorizedConversationIds = setOf(CONV_A.value),
    )

    @Test
    fun canonicalContractIncludesScopedSubagentMethods() {
        assertTrue(LIST_METHOD.value in AdminRpcRegistry.canonicalMethods)
        assertTrue(TODOS_METHOD.value in AdminRpcRegistry.canonicalMethods)
    }

    @Test
    fun listFiltersByConversationAndAgentScope() = runTest {
        val source = RecordingSource(
            entries = listOf(
                entry(TOOL_WANTED, CONV_A, AGENT_A),
                entry(TOOL_OTHER_CONV, CONV_B, AGENT_A),
                entry(TOOL_OTHER_AGENT, CONV_A, AGENT_B),
            ),
        )
        val response = invoke(
            source,
            LIST_METHOD,
            buildJsonObject {
                put(KEY_CONVERSATION, CONV_A.value)
                put(KEY_AGENT, AGENT_A.value)
                put(KEY_ALL, true)
            },
            scopedA,
        )

        assertTrue(response.success)
        assertEquals(listOf(TOOL_WANTED.value), response.toolCallIds)
        assertEquals(listOf(RecordedCall.list(CONV_A, includeTerminal = true)), source.calls)
    }

    @Test
    fun listDeniesMissingScopeWithoutReadingSource() = runTest {
        val source = RecordingSource()
        val response = invoke(source, LIST_METHOD, buildJsonObject {}, AdminRpcRequestContext.Authenticated)
        assertFalse(response.success)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun listDeniesUnauthorizedConversationWithoutReadingSource() = runTest {
        val source = RecordingSource()
        val response = invoke(source, LIST_METHOD, conversationParams(CONV_B), scopedA)
        assertEquals(ERROR_FORBIDDEN, response.error)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun listDeniesUnauthenticatedCallerWithoutReadingSource() = runTest {
        val source = RecordingSource()
        val response = invoke(source, LIST_METHOD, conversationParams(CONV_A), AdminRpcRequestContext.Unauthenticated)
        assertEquals(ERROR_UNAUTHORIZED, response.error)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun todosMapsAuthoritativeSnapshot() = runTest {
        val todo = SubagentTodo(TODO_CONTENT, TODO_STATUS, TODO_ACTIVE)
        val source = RecordingSource(
            todosSnapshot = SubagentTodosSnapshot(entry(TOOL_WANTED, CONV_A, AGENT_A), listOf(todo), true),
        )
        val response = invoke(source, TODOS_METHOD, todosParams(CONV_A, TOOL_WANTED), scopedA)
        assertTrue(response.found)
        assertTrue(response.todosFound)
        assertEquals(TODO_CONTENT, response.firstTodoContent)
        assertEquals(listOf(RecordedCall.todos(CONV_A, TOOL_WANTED)), source.calls)
    }

    @Test
    fun todosRejectsCrossConversationSnapshot() = runTest {
        val todo = SubagentTodo(TODO_CONTENT, TODO_STATUS, TODO_ACTIVE)
        val source = RecordingSource(
            todosSnapshot = SubagentTodosSnapshot(entry(TOOL_WANTED, CONV_B, AGENT_A), listOf(todo), true),
        )
        val response = invoke(source, TODOS_METHOD, todosParams(CONV_A, TOOL_WANTED), scopedA)
        assertFalse(response.found)
    }

    private suspend fun invoke(
        source: SubagentRegistrySource,
        method: AdminMethod,
        params: JsonObject,
        context: AdminRpcRequestContext,
    ): ParsedResponse {
        val router = AdminRpcRouter().also { SubagentAdminHandlers.register(it, source) }
        val raw = router.dispatch(
            AdminRpcInvocation(REQUEST_ID, method.value, params, context),
        )
        return ParsedResponse(json.parseToJsonElement(raw).jsonObject)
    }

    private fun conversationParams(conversationId: ConversationRef) = buildJsonObject {
        put(KEY_CONVERSATION, conversationId.value)
    }

    private fun todosParams(conversationId: ConversationRef, toolCallId: ToolCallRef) = buildJsonObject {
        put(KEY_CONVERSATION, conversationId.value)
        put(KEY_TOOL_CALL, toolCallId.value)
    }

    private fun entry(toolCallId: ToolCallRef, conversationId: ConversationRef, agentId: AgentRef) = SubagentEntry(
        toolCallId = toolCallId.value,
        status = SubagentStatus.RUNNING,
        parentConversationId = conversationId.value,
        parentAgentId = agentId.value,
    )

    private class RecordingSource(
        private val entries: List<SubagentEntry> = emptyList(),
        var todosSnapshot: SubagentTodosSnapshot? = null,
    ) : SubagentRegistrySource {
        val calls = mutableListOf<RecordedCall>()

        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> {
            calls += RecordedCall.list(ConversationRef(conversationId), includeTerminal)
            return entries
        }

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
            calls += RecordedCall.todos(ConversationRef(conversationId), ToolCallRef(toolCallId))
            return todosSnapshot
        }
    }

    private data class ParsedResponse(private val obj: JsonObject) {
        val success: Boolean get() = obj["success"]!!.jsonPrimitive.content.toBoolean()
        val error: String? get() = obj["error"]?.jsonPrimitive?.content
        val toolCallIds: List<String>
            get() = obj["result"]!!.jsonObject["subagents"]!!.jsonArray.map {
                it.jsonObject["toolCallId"]!!.jsonPrimitive.content
            }
        val found: Boolean get() = obj["result"]!!.jsonObject["found"]!!.jsonPrimitive.content.toBoolean()
        val todosFound: Boolean get() = obj["result"]!!.jsonObject["todos_found"]!!.jsonPrimitive.content.toBoolean()
        val firstTodoContent: String
            get() = obj["result"]!!.jsonObject["todos"]!!.jsonArray.single()
                .jsonObject["content"]!!.jsonPrimitive.content
    }

    private data class RecordedCall(
        val conversationId: String,
        val includeTerminal: Boolean,
        val toolCallId: String?,
    ) {
        companion object {
            fun list(conversationId: ConversationRef, includeTerminal: Boolean) =
                RecordedCall(conversationId.value, includeTerminal, null)

            fun todos(conversationId: ConversationRef, toolCallId: ToolCallRef) =
                RecordedCall(conversationId.value, false, toolCallId.value)
        }
    }

    @JvmInline private value class AdminMethod(val value: String)
    @JvmInline private value class ConversationRef(val value: String)
    @JvmInline private value class AgentRef(val value: String)
    @JvmInline private value class ToolCallRef(val value: String)

    private companion object {
        val LIST_METHOD = AdminMethod("subagent.list")
        val TODOS_METHOD = AdminMethod("subagent.todos")
        val CONV_A = ConversationRef("conv-a")
        val CONV_B = ConversationRef("conv-b")
        val AGENT_A = AgentRef("agent-a")
        val AGENT_B = AgentRef("agent-b")
        val TOOL_WANTED = ToolCallRef("wanted")
        val TOOL_OTHER_CONV = ToolCallRef("other-conversation")
        val TOOL_OTHER_AGENT = ToolCallRef("other-agent")
        const val REQUEST_ID = "request"
        const val KEY_CONVERSATION = "conversation_id"
        const val KEY_AGENT = "agent_id"
        const val KEY_ALL = "all"
        const val KEY_TOOL_CALL = "tool_call_id"
        const val ERROR_FORBIDDEN = "forbidden"
        const val ERROR_UNAUTHORIZED = "unauthorized"
        const val TODO_CONTENT = "Ship parity"
        const val TODO_STATUS = "in_progress"
        const val TODO_ACTIVE = "Shipping parity"
    }
}
