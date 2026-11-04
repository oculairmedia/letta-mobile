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

    @Test
    fun dismissPersistsExactScopedRecordAndPublishes() = runTest {
        val store = com.letta.mobile.data.subagents.FileSubagentRegistryStore(
            java.nio.file.Files.createTempDirectory("subagent-dismiss-test").resolve("registry.json"),
        )
        val registry = com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store, clock = { 1000L })
        val source = ControllerSubagentRegistrySource(registry)
        source.ingestFromSource("conv-a", "agent-a", listOf(
            entry(TOOL_WANTED, CONV_A, AGENT_A),
            entry(TOOL_OTHER_AGENT, CONV_A, AGENT_A),
        ), com.letta.mobile.data.subagents.SubagentChipSource.CONTROLLER_NATIVE)
        val changed = mutableListOf<SubagentConversationKey>()
        source.changeListener = SubagentRegistryChangeListener { changed += it }
        val fixture = requireNotNull(javaClass.getResource("/subagents/dismiss-request.json")).readText()
        val response = invoke(source, AdminMethod("subagent.dismiss"), json.parseToJsonElement(fixture).jsonObject, scopedA)
        assertTrue(response.success)
        assertEquals(listOf("wanted"), response.toolCallIds)
        assertEquals("user_dismissed", registry.record("conv-a", "agent-a", "wanted")?.terminalReason)
        assertEquals(com.letta.mobile.data.subagents.SubagentChipState.ORPHANED, registry.record("conv-a", "agent-a", "wanted")?.state)
        assertEquals(com.letta.mobile.data.subagents.SubagentChipState.RUNNING, registry.record("conv-a", "agent-a", "other-agent")?.state)
        assertEquals(listOf(SubagentConversationKey("agent-a", "conv-a")), changed)
        val restarted = com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store, clock = { 1001L })
        assertEquals("user_dismissed", restarted.record("conv-a", "agent-a", "wanted")?.terminalReason)
    }

    @Test
    fun dismissalDeniesOtherConversationAndMissingIdentity() = runTest {
        val store = com.letta.mobile.data.subagents.FileSubagentRegistryStore(
            java.nio.file.Files.createTempDirectory("subagent-scope-test").resolve("registry.json"),
        )
        val source = ControllerSubagentRegistrySource(com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store))
        source.changeListener = SubagentRegistryChangeListener { }
        source.ingestFromSource("conv-a", "agent-a", listOf(entry(TOOL_WANTED, CONV_A, AGENT_A)),
            com.letta.mobile.data.subagents.SubagentChipSource.CONTROLLER_NATIVE)
        val other = buildJsonObject {
            put(KEY_CONVERSATION, "conv-b")
            put(KEY_AGENT, "agent-a")
            put(KEY_TOOL_CALL, "wanted")
        }
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), other, scopedA).success)
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), todosParams(CONV_A, TOOL_WANTED), scopedA).success)
        val wrongAgent = buildJsonObject {
            put(KEY_CONVERSATION, "conv-a")
            put(KEY_AGENT, "agent-b")
            put(KEY_TOOL_CALL, "wanted")
        }
        val restricted = scopedA.copy(authorizedAgentIds = setOf("agent-a"))
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), wrongAgent, restricted).success)
        val fixture = requireNotNull(javaClass.getResource("/subagents/dismiss-request.json")).readText()
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), json.parseToJsonElement(fixture).jsonObject,
            AdminRpcRequestContext.Unauthenticated).success)
        assertEquals(SubagentStatus.RUNNING, source.list("conv-a", true).single().status)
    }

    @Test
    fun lateRunningCannotReviveDismissalAfterReload() = runTest {
        val path = java.nio.file.Files.createTempDirectory("subagent-reload-test").resolve("registry.json")
        val store = com.letta.mobile.data.subagents.FileSubagentRegistryStore(path)
        val source = ControllerSubagentRegistrySource(com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store))
        source.changeListener = SubagentRegistryChangeListener { }
        val running = entry(TOOL_WANTED, CONV_A, AGENT_A)
        val native = com.letta.mobile.data.subagents.SubagentChipSource.CONTROLLER_NATIVE
        source.ingestFromSource("conv-a", "agent-a", listOf(running), native)
        val fixture = requireNotNull(javaClass.getResource("/subagents/dismiss-request.json")).readText()
        assertTrue(invoke(source, AdminMethod("subagent.dismiss"), json.parseToJsonElement(fixture).jsonObject, scopedA).success)
        val restarted = ControllerSubagentRegistrySource(com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store))
        restarted.ingestFromSource("conv-a", "agent-a", listOf(running), native)
        assertEquals("user_dismissed", restarted.registry.record("conv-a", "agent-a", "wanted")?.terminalReason)
        assertEquals(SubagentStatus.CANCELLED, restarted.list("conv-a", false).single().status)
    }

    @Test
    fun numericAgentIdCannotBypassAgentScope() = runTest {
        val store = com.letta.mobile.data.subagents.FileSubagentRegistryStore(
            java.nio.file.Files.createTempDirectory("subagent-agent-scope-test").resolve("registry.json"),
        )
        val source = ControllerSubagentRegistrySource(com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store))
        source.ingestFromSource("conv-a", "1", listOf(entry(ToolCallRef("numeric"), CONV_A, AgentRef("1"))),
            com.letta.mobile.data.subagents.SubagentChipSource.CONTROLLER_NATIVE)
        source.changeListener = SubagentRegistryChangeListener { }
        val numericAgent = buildJsonObject {
            put(KEY_CONVERSATION, "conv-a")
            put(KEY_AGENT, 1)
            put(KEY_TOOL_CALL, "numeric")
        }
        val restricted = scopedA.copy(authorizedAgentIds = setOf("agent-a"))
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), numericAgent, restricted).success)
        assertEquals(SubagentStatus.RUNNING, source.list("conv-a", true).single().status)
    }

    @Test
    fun dismissalCapabilityRequiresDurableStorageAndPublisher() {
        val ephemeral = ControllerSubagentRegistrySource().also { it.changeListener = SubagentRegistryChangeListener { } }
        val noPublisher = ControllerSubagentRegistrySource(com.letta.mobile.data.subagents.DurableSubagentRegistry(
            store = com.letta.mobile.data.subagents.FileSubagentRegistryStore(
                java.nio.file.Files.createTempDirectory("subagent-capability-test").resolve("registry.json"),
            ),
        ))
        listOf(ephemeral, noPublisher).forEach { source ->
            val router = AdminRpcRouter().also { SubagentAdminHandlers.register(it, source) }
            assertFalse("subagent.dismiss" in router.registeredMethods)
            assertFalse("subagent_dismiss_v1" in IrohNodeConnection.advertisedCapabilities(router))
        }
        noPublisher.changeListener = SubagentRegistryChangeListener { }
        val router = AdminRpcRouter().also { SubagentAdminHandlers.register(it, noPublisher) }
        assertTrue("subagent_dismiss_v1" in IrohNodeConnection.advertisedCapabilities(router))
        assertEquals(IrohPeerCapabilities.forAdminMethod("conversation.update"), IrohPeerCapabilities.forAdminMethod("subagent.dismiss"))
        val required = IrohPeerCapabilities.forAdminMethod("subagent.dismiss")
        assertFalse(IrohPeerCapabilities.isAllowed(setOf(IrohPeerCapabilities.CHAT_READ), required))
        assertFalse(IrohPeerCapabilities.isAllowed(setOf(IrohPeerCapabilities.CHAT_SEND), required))
        assertTrue(IrohPeerCapabilities.isAllowed(setOf(IrohPeerCapabilities.CONVERSATION_MANAGE), required))
    }

    @Test
    fun storageFailureDoesNotAcknowledgeOrPublishDismissal() = runTest {
        val store = FailingStore()
        val source = ControllerSubagentRegistrySource(com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store))
        source.ingestFromSource("conv-a", "agent-a", listOf(entry(TOOL_WANTED, CONV_A, AGENT_A)),
            com.letta.mobile.data.subagents.SubagentChipSource.CONTROLLER_NATIVE)
        var pushes = 0
        source.changeListener = SubagentRegistryChangeListener { pushes++ }
        store.failWrites = true
        val fixture = requireNotNull(javaClass.getResource("/subagents/dismiss-request.json")).readText()
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), json.parseToJsonElement(fixture).jsonObject, scopedA).success)
        assertEquals(0, pushes)
        assertEquals(SubagentStatus.RUNNING, source.list("conv-a", true).single().status)
    }

    @Test
    fun fullDismissalBudgetReturnsFailureWithoutPublishingOrHidingWork() = runTest {
        val store = FailingStore()
        val source = ControllerSubagentRegistrySource(
            com.letta.mobile.data.subagents.DurableSubagentRegistry(store = store, maxEntries = 1),
        )
        val native = com.letta.mobile.data.subagents.SubagentChipSource.CONTROLLER_NATIVE
        source.ingestFromSource("conv-a", "agent-a", listOf(entry(TOOL_WANTED, CONV_A, AGENT_A)), native)
        var pushes = 0
        source.changeListener = SubagentRegistryChangeListener { pushes++ }
        val fixture = requireNotNull(javaClass.getResource("/subagents/dismiss-request.json")).readText()
        val params = json.parseToJsonElement(fixture).jsonObject
        assertTrue(invoke(source, AdminMethod("subagent.dismiss"), params, scopedA).success)
        source.ingestFromSource("conv-a", "agent-a", listOf(entry(ToolCallRef("second"), CONV_A, AGENT_A)), native)
        pushes = 0
        val second = buildJsonObject {
            put(KEY_CONVERSATION, "conv-a")
            put(KEY_AGENT, "agent-a")
            put(KEY_TOOL_CALL, "second")
        }
        assertFalse(invoke(source, AdminMethod("subagent.dismiss"), second, scopedA).success)
        assertEquals(0, pushes)
        assertEquals(SubagentStatus.RUNNING, source.list("conv-a", true).single { it.toolCallId == "second" }.status)
    }

    private class FailingStore : com.letta.mobile.data.subagents.SubagentRegistryStore {
        override val durable = true
        var failWrites = false
        private var records = emptyList<com.letta.mobile.data.subagents.SubagentChipRecord>()
        override fun load() = records
        override fun save(records: List<com.letta.mobile.data.subagents.SubagentChipRecord>) {
            check(!failWrites) { "write failed" }
            this.records = records
        }
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
