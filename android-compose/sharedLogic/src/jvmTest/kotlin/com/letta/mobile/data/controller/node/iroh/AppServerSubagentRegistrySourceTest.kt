package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppServerSubagentRegistrySourceTest {
    @Test
    fun `authoritative snapshots remain conversation scoped`() = runTest {
        val source = AppServerSubagentRegistrySource(EmptyClient, this)
        source.ingest(update("conv-a", "tc-a", "running"))
        source.ingest(update("conv-b", "tc-b", "completed"))

        assertEquals(listOf("tc-a"), source.list("conv-a", includeTerminal = false).map { it.toolCallId })
        assertTrue(source.list("conv-b", includeTerminal = false).isEmpty())
        assertEquals(listOf("tc-b"), source.list("conv-b", includeTerminal = true).map { it.toolCallId })
        assertNull(source.todos("conv-a", "tc-b"))
    }

    @Test
    fun `dispatch projection resolves subagent TodoWrite by authoritative subagent id`() = runTest {
        val source = AppServerSubagentRegistrySource(EmptyClient, this)
        source.ingest(agentDispatch("conv-a", "tc-a"))
        source.ingest(todoWrite("conv-a", "tc-a"))

        val snapshot = assertNotNull(source.todos("conv-a", "tc-a"))
        assertEquals("Implement fix", snapshot.subagent.description)
        assertTrue(snapshot.todosFound)
        assertEquals("Run tests", snapshot.todos.single().content)
        assertFalse(snapshot.todos.isEmpty())
    }

    private fun update(conversationId: String, toolCallId: String, status: String) =
        AppServerInboundFrame.UpdateSubagentState(
            runtime = runtime(conversationId),
            eventSeq = 1,
            emittedAt = "now",
            idempotencyKey = "update-$conversationId",
            subagents = listOf(buildJsonObject {
                put("toolCallId", toolCallId)
                put("status", status)
            }),
        )

    private fun agentDispatch(conversationId: String, toolCallId: String) = streamDelta(
        conversationId,
        buildJsonObject {
            put("message_type", "tool_call_message")
            put("tool_call", buildJsonObject {
                put("name", "Agent")
                put("tool_call_id", toolCallId)
                put("arguments", buildJsonObject { put("description", "Implement fix") })
            })
        },
    )

    private fun todoWrite(conversationId: String, subagentId: String) = streamDelta(
        conversationId,
        buildJsonObject {
            put("message_type", "tool_call_message")
            put("tool_call", buildJsonObject {
                put("name", "TodoWrite")
                put("tool_call_id", "todo-1")
                put("arguments", buildJsonObject {
                    put("todos", buildJsonArray {
                        add(buildJsonObject {
                            put("content", "Run tests")
                            put("status", "in_progress")
                            put("activeForm", "Running tests")
                        })
                    })
                })
            })
        },
        subagentId,
    )

    private fun streamDelta(conversationId: String, delta: kotlinx.serialization.json.JsonObject, subagentId: String? = null) =
        AppServerInboundFrame.StreamDelta(
            runtime = runtime(conversationId),
            eventSeq = 1,
            emittedAt = "now",
            idempotencyKey = "delta-$conversationId-${delta.hashCode()}",
            delta = delta,
            subagentId = subagentId,
        )

    private fun runtime(conversationId: String) = AppServerRuntimeScope("agent-a", conversationId)

    private object EmptyClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
