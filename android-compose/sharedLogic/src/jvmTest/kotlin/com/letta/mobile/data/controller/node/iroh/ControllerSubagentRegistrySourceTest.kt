package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerSubagentRegistrySourceTest {
    @Test
    fun updateSubagentStateHydratesConversationScopedList() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t",
                idempotencyKey = "k",
                subagents = listOf(
                    buildJsonObject {
                        put("toolCallId", "tool/1")
                        put("description", "Ship fix")
                        put("status", SubagentStatus.RUNNING)
                        put("parentConversationId", "conv-a")
                        put("parentAgentId", "agent-1")
                    },
                ),
            ),
        )

        val running = source.list("conv-a", includeTerminal = false)
        assertEquals(listOf("tool/1"), running.map { it.toolCallId })
        assertEquals("Ship fix", running.single().description)
        assertEquals(emptyList(), source.list("conv-other", includeTerminal = true))
    }

    @Test
    fun includeTerminalFiltersRunningOnlyWhenFalse() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.replaceConversation(
            "conv-a",
            listOf(
                SubagentEntry(toolCallId = "run", status = SubagentStatus.RUNNING, parentConversationId = "conv-a"),
                SubagentEntry(toolCallId = "done", status = SubagentStatus.COMPLETED, parentConversationId = "conv-a"),
            ),
        )
        assertEquals(listOf("run"), source.list("conv-a", includeTerminal = false).map { it.toolCallId })
        assertEquals(setOf("run", "done"), source.list("conv-a", includeTerminal = true).map { it.toolCallId }.toSet())
    }

    @Test
    fun capabilityAdvertisedOnlyWhenSubagentRoutesRegistered() {
        val withoutSource = AdminRpcRegistry.buildRouter(controller = null, subagentRegistrySource = null)
        val withSource = AdminRpcRegistry.buildRouter(
            controller = null,
            subagentRegistrySource = ControllerSubagentRegistrySource(),
        )
        assertFalse(ControllerSubagentRegistrySource.CAPABILITY in IrohNodeConnection.advertisedCapabilities(withoutSource))
        assertTrue(ControllerSubagentRegistrySource.CAPABILITY in IrohNodeConnection.advertisedCapabilities(withSource))
    }
}
