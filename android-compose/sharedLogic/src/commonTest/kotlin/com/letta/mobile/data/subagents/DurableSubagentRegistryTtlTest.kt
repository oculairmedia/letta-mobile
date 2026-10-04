package com.letta.mobile.data.subagents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-fxoew.3: live chips nobody reports end as stale instead of
 * spinning forever and pinning the capacity cap.
 *
 * Kotlin/Native commonTest naming: punctuation-free camelCase only.
 */
class DurableSubagentRegistryTtlTest {
    private var now = 1_000L

    private fun registry(
        store: SubagentRegistryStore = InMemorySubagentRegistryStore(),
        maxEntries: Int = DurableSubagentRegistry.MAX_ENTRIES,
    ) = DurableSubagentRegistry(store = store, maxEntries = maxEntries, clock = { now })

    private fun running(toolCallId: String, conversationId: String = "conv-a") = SubagentChipObservation(
        conversationId = conversationId,
        agentId = "agent-1",
        toolCallId = toolCallId,
        state = SubagentChipState.RUNNING,
        source = SubagentChipSource.CONTROLLER_NATIVE,
    )

    @Test
    fun runningChipOlderThanTheTtlEndsAsStale() {
        val reg = registry()
        reg.observe(running("tool-old"))
        now += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS + 1

        val expired = reg.expireStale()

        assertEquals(listOf("tool-old"), expired.map { it.toolCallId })
        val record = reg.findByToolCall("conv-a", "tool-old")
        assertEquals(SubagentChipState.CANCELLED, record?.state)
        assertEquals(SubagentChipRecord.TERMINAL_REASON_STALE, record?.terminalReason)
        assertEquals(now, record?.terminalAtEpochMs)
    }

    @Test
    fun freshRunningChipIsNotExpired() {
        val reg = registry()
        reg.observe(running("tool-fresh"))
        now += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS

        assertEquals(emptyList(), reg.expireStale())
        val record = reg.findByToolCall("conv-a", "tool-fresh")
        assertEquals(SubagentChipState.RUNNING, record?.state)
        assertNull(record?.terminalReason)
    }

    @Test
    fun staleRunningChipsDoNotBlockInsertionAtTheCap() {
        val cap = DurableSubagentRegistry.MAX_ENTRIES
        val reg = registry()
        repeat(cap) { index -> reg.observe(running("tool-$index")) }
        assertEquals(cap, reg.liveCount())
        now += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS + 1

        reg.observe(running("tool-new"))

        assertEquals(cap, reg.size())
        assertEquals(1, reg.liveCount())
        assertEquals(SubagentChipState.RUNNING, reg.findByToolCall("conv-a", "tool-new")?.state)
    }

    @Test
    fun restartEndsPersistedZombies() {
        val store = InMemorySubagentRegistryStore()
        registry(store).observe(running("tool-zombie"))
        now += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS + 1

        val restarted = registry(store)

        assertEquals(0, restarted.liveCount())
        assertEquals(SubagentChipRecord.TERMINAL_REASON_STALE, store.load().single().terminalReason)
    }
}
