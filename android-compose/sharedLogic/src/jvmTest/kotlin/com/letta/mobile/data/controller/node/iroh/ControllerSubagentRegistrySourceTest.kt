package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.subagents.SubagentChipState
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

    @Test
    fun snakeCaseSnapshotMapsPendingErrorAndNumericStartTime() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 2,
                emittedAt = "t",
                idempotencyKey = "k2",
                subagents = listOf(
                    buildJsonObject {
                        put("subagent_id", "sa-1")
                        put("status", "pending")
                        put("conversation_id", "sub-conv")
                        put("start_time", 1_700_000_000L)
                    },
                    buildJsonObject {
                        put("tool_call_id", "tool/err")
                        put("status", "running")
                        put("error", "boom")
                    },
                ),
            ),
        )

        val running = source.list("conv-a", includeTerminal = false)
        assertEquals(listOf("sa-1"), running.map { it.toolCallId })
        assertEquals(SubagentStatus.RUNNING, running.single().status)
        assertEquals("sub-conv", running.single().subagentConversationId)
        assertEquals("1700000000000", running.single().startedAt)

        val all = source.list("conv-a", includeTerminal = true)
        val failed = all.single { it.toolCallId == "tool/err" }
        assertEquals(SubagentStatus.FAILED, failed.status)
    }

    /**
     * lgns8.22.8: a chip absent from the newest authoritative snapshot is
     * RECONCILED to orphaned, not deleted. Before this bead it was removed
     * outright, which is how a live worker's chip could silently vanish.
     */
    @Test
    fun laterUpdateSubagentStateOrphansPriorSnapshotIdentitiesInsteadOfDeletingThem() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t1",
                idempotencyKey = "k1",
                subagents = listOf(
                    buildJsonObject {
                        put("subagent_id", "sa-old")
                        put("status", "pending")
                    },
                ),
            ),
        )
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 2,
                emittedAt = "t2",
                idempotencyKey = "k2",
                subagents = listOf(
                    buildJsonObject {
                        put("tool_call_id", "tool/new")
                        put("subagent_id", "sa-new")
                        put("status", "running")
                    },
                ),
            ),
        )

        assertEquals(listOf("tool/new"), source.list("conv-a", includeTerminal = false).map { it.toolCallId })
        val all = source.list("conv-a", includeTerminal = true)
        assertEquals(setOf("sa-old", "tool/new"), all.map { it.toolCallId }.toSet())
        assertEquals(SubagentStatus.CANCELLED, all.single { it.toolCallId == "sa-old" }.status)
        assertEquals(
            SubagentChipState.ORPHANED,
            source.registry.record("conv-a", "agent-1", "sa-old")?.state,
        )
    }

    /** lgns8.22.8: a durable-store-backed source rehydrates across a restart. */
    @Test
    fun sourceRehydratesChipsAcrossAControllerRestart() = runTest {
        val store = InMemorySubagentRegistryStore()
        val before = ControllerSubagentRegistrySource(DurableSubagentRegistry(store = store))
        before.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t1",
                idempotencyKey = "k1",
                subagents = listOf(
                    buildJsonObject {
                        put("toolCallId", "tool/1")
                        put("status", SubagentStatus.RUNNING)
                    },
                ),
            ),
        )

        val after = ControllerSubagentRegistrySource(DurableSubagentRegistry(store = store))

        assertEquals(listOf("tool/1"), after.list("conv-a", includeTerminal = false).map { it.toolCallId })
        assertEquals(listOf("tool/1"), after.replaySnapshot("conv-a").map { it.toolCallId })
    }

    /** letta-mobile-7vs4s: a weaker source cannot overwrite controller-native truth. */
    @Test
    fun httpAndCorrelatorSourcesCannotOverwriteControllerNativeChips() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t1",
                idempotencyKey = "k1",
                subagents = listOf(
                    buildJsonObject {
                        put("toolCallId", "tool/1")
                        put("status", SubagentStatus.COMPLETED)
                    },
                ),
            ),
        )

        source.ingestFromSource(
            conversationId = "conv-a",
            agentId = "agent-1",
            entries = listOf(SubagentEntry(toolCallId = "tool/1", status = SubagentStatus.RUNNING)),
            source = SubagentChipSource.HTTP_REGISTRY,
        )

        assertTrue(source.list("conv-a", includeTerminal = false).isEmpty())
        assertEquals(
            SubagentChipState.COMPLETED,
            source.registry.record("conv-a", "agent-1", "tool/1")?.state,
        )
    }
}
