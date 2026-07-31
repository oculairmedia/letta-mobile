package com.letta.mobile.data.subagents

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-lgns8.22.8: the durable half of the registry — a controller
 * restart must find its chips on disk, and a corrupt file must not brick boot.
 */
class FileSubagentRegistryStoreTest {

    @Test
    fun chipsSurviveAControllerRestartOnDisk() {
        val dir = Files.createTempDirectory("subagent-registry")
        val path = dir.resolve("subagents.json")

        val first = DurableSubagentRegistry(store = FileSubagentRegistryStore(path), clock = { 1_000 })
        first.observe(
            SubagentChipObservation(
                conversationId = "conv-a",
                agentId = "agent-1",
                toolCallId = "tool/1",
                state = SubagentChipState.RUNNING,
                source = SubagentChipSource.CONTROLLER_NATIVE,
                description = "ship fix",
            ),
        )
        assertTrue(Files.exists(path))

        // Fresh process: brand new store instance over the same path.
        val restarted = DurableSubagentRegistry(store = FileSubagentRegistryStore(path), clock = { 2_000 })

        val record = restarted.record("conv-a", "agent-1", "tool/1")
        assertNotNull(record)
        assertEquals("ship fix", record.description)
        assertEquals(SubagentChipState.RUNNING, record.state)

        // …and reconciling against an empty live set orphans it durably.
        restarted.reconcile("conv-a", emptySet())
        val afterSecondRestart =
            DurableSubagentRegistry(store = FileSubagentRegistryStore(path), clock = { 3_000 })
        assertEquals(
            SubagentChipState.ORPHANED,
            afterSecondRestart.record("conv-a", "agent-1", "tool/1")?.state,
        )
    }

    @Test
    fun corruptRegistryFileLoadsAsEmpty() {
        val dir = Files.createTempDirectory("subagent-registry-corrupt")
        val path = dir.resolve("subagents.json")
        Files.write(path, "{not json".toByteArray())

        val store = FileSubagentRegistryStore(path)

        assertEquals(emptyList(), store.load())
        assertEquals(0, DurableSubagentRegistry(store = store).size())
    }

    @Test
    fun missingFileLoadsAsEmpty() {
        val dir = Files.createTempDirectory("subagent-registry-missing")
        assertEquals(emptyList(), FileSubagentRegistryStore(dir.resolve("nope.json")).load())
    }
}
