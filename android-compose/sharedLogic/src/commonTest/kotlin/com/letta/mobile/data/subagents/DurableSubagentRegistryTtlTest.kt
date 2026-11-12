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

    private fun restartWithIdentity(previous: String?, current: String?): DurableSubagentRegistry {
        val record = SubagentChipRecord(
            conversationId = "conv-a", agentId = "agent-1", toolCallId = "tool-process",
            state = SubagentChipState.RUNNING, source = SubagentChipSource.CONTROLLER_NATIVE,
            firstSeenEpochMs = now, lastSeenEpochMs = now, appServerProcessIdentity = previous,
        )
        return DurableSubagentRegistry(
            store = InMemorySubagentRegistryStore(listOf(record)), clock = { now },
            processIdentity = AppServerProcessIdentity { current },
        )
    }

    @Test
    fun differentKnownProcessRetiresImmediatelyAndRefusesReplay() {
        val restarted = restartWithIdentity("boot:20:100", "boot:20:200")
        val record = restarted.findByToolCall("conv-a", "tool-process")
        assertEquals(SubagentChipState.CANCELLED, record?.state)
        assertEquals("app_server_restarted", record?.terminalReason)
        assertEquals(now, record?.terminalAtEpochMs)
        assertEquals(true, restarted.observe(running("tool-process")) is DurableSubagentRegistry.ObserveResult.IllegalTransition)
    }

    @Test
    fun sameKnownProcessProtectsSilentWorkerBeyondTheRestartBound() {
        val restarted = restartWithIdentity("boot:20:100", "boot:20:100")
        now += 61 * 60_000L
        assertEquals(emptyList(), restarted.expireStale())
        assertEquals(SubagentChipState.RUNNING, restarted.findByToolCall("conv-a", "tool-process")?.state)
    }

    @Test
    fun eitherUnknownIdentityKeepsUncertaintyFallback() {
        listOf(null to "boot:20:100", "boot:20:100" to null, null to null).forEach { (old, current) ->
            val restarted = restartWithIdentity(old, current)
            assertEquals(1, restarted.liveCount())
            now += 61 * 60_000L
            assertEquals("restart_unobserved", restarted.expireStale().single().terminalReason)
        }
    }

    @Test
    fun legacyIdentityWithKnownCurrentProcessRetainsFullUncertaintyGrace() {
        val restarted = restartWithIdentity(null, "boot:20:100")
        assertEquals(SubagentChipState.RUNNING, restarted.findByToolCall("conv-a", "tool-process")?.state)
        assertNull(restarted.findByToolCall("conv-a", "tool-process")?.appServerProcessIdentity)
        now += 60 * 60_000L
        assertEquals(emptyList(), restarted.expireStale())
        assertEquals(SubagentChipState.RUNNING, restarted.findByToolCall("conv-a", "tool-process")?.state)
        now++
        val retired = restarted.expireStale().single()
        assertEquals(SubagentChipState.CANCELLED, retired.state)
        assertEquals("restart_unobserved", retired.terminalReason)
        assertNull(retired.appServerProcessIdentity)
    }

    @Test
    fun processIdentityRoundTripsAndLegacyJsonDefaultsToUnknown() {
        val reg = restartWithIdentity("boot:20:100", "boot:20:100")
        val record = requireNotNull(reg.findByToolCall("conv-a", "tool-process"))
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(SubagentChipRecord.serializer(), record)
        assertEquals(record, json.decodeFromString(SubagentChipRecord.serializer(), encoded))
        val legacy = encoded.replace(",\"app_server_process_identity\":\"boot:20:100\"", "")
        assertEquals(null, json.decodeFromString(SubagentChipRecord.serializer(), legacy).appServerProcessIdentity)
    }

    @Test
    fun firstObservationPersistsKnownProcessIdentity() {
        val store = InMemorySubagentRegistryStore()
        val reg = DurableSubagentRegistry(store = store, clock = { now }, processIdentity = AppServerProcessIdentity { "boot:20:100" })
        reg.observe(running("tool-new"))
        assertEquals("boot:20:100", store.load().single().appServerProcessIdentity)
    }

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
    fun restartBoundsUnobservedLegacyRecordsWithoutClaimingWorkerDeath() {
        val store = InMemorySubagentRegistryStore()
        registry(store).observe(running("tool-unobserved"))
        val restarted = registry(store)
        now += 60 * 60_000L
        assertEquals(emptyList(), restarted.expireStale())
        now++
        val retired = restarted.expireStale()
        assertEquals(listOf("tool-unobserved"), retired.map { it.toolCallId })
        val expired = retired.single()
        assertEquals(SubagentChipState.CANCELLED, expired.state)
        assertEquals("restart_unobserved", expired.terminalReason)
        assertEquals(emptyList(), restarted.expireStale())
        assertEquals(
            true,
            restarted.observe(running("tool-unobserved")) is DurableSubagentRegistry.ObserveResult.IllegalTransition,
        )
    }

    @Test
    fun wrapperRestartProtectsReobservedWorkerForTheFullTtl() {
        val store = InMemorySubagentRegistryStore()
        registry(store).observe(running("tool-live"))
        val restarted = registry(store)
        now += 30 * 60_000L
        restarted.observe(running("tool-live"))
        now += 31 * 60_000L
        assertEquals(emptyList(), restarted.expireStale())
        assertEquals(SubagentChipState.RUNNING, restarted.findByToolCall("conv-a", "tool-live")?.state)
    }

    @Test
    fun wrapperRestartOmissionDoesNotImmediatelyOrphanSilentWorker() {
        val store = InMemorySubagentRegistryStore()
        registry(store).observe(running("tool-silent"))
        val restarted = registry(store)
        repeat(2) {
            val result = restarted.reconcileSnapshot(
                com.letta.mobile.data.model.ConversationId("conv-a"),
                com.letta.mobile.data.model.AgentId("agent-1"),
                emptySet(), 1L,
            )
            assertEquals(emptyList(), result.orphaned)
            now += 30 * 60_000L
        }
        assertEquals(emptyList(), restarted.expireStale())
        assertEquals(SubagentChipState.RUNNING, restarted.findByToolCall("conv-a", "tool-silent")?.state)
    }

    @Test
    fun newWorkersDoNotInheritTheRestartBound() {
        val reg = registry()
        reg.observe(running("tool-new"))
        now += 61 * 60_000L
        assertEquals(emptyList(), reg.expireStale())
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
