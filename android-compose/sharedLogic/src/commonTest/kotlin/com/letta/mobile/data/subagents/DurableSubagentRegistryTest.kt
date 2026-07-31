package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-lgns8.22.8 (+ 7vs4s source selection, 5hihw lifecycle mapping).
 *
 * Kotlin/Native commonTest naming: punctuation-free camelCase only.
 */
class DurableSubagentRegistryTest {

    private var now = 1_000L

    private fun registry(
        store: SubagentRegistryStore,
        maxEntries: Int = DurableSubagentRegistry.MAX_ENTRIES,
    ) = DurableSubagentRegistry(store = store, maxEntries = maxEntries, clock = { now })

    private fun observation(
        conversationId: String = "conv-a",
        agentId: String? = "agent-1",
        toolCallId: String = "tool/1",
        state: SubagentChipState = SubagentChipState.RUNNING,
        source: SubagentChipSource = SubagentChipSource.CONTROLLER_NATIVE,
        description: String = "",
        generation: Long = 0,
    ) = SubagentChipObservation(
        conversationId = conversationId,
        agentId = agentId,
        toolCallId = toolCallId,
        state = state,
        source = source,
        description = description,
        generation = generation,
    )

    // -------------------------------------------------- (a) restart survival

    @Test
    fun restartRehydratesChipsFromTheStore() {
        val store = InMemorySubagentRegistryStore()
        val first = registry(store)
        first.observe(observation(toolCallId = "tool/1", description = "ship fix"))
        first.observe(observation(toolCallId = "tool/2", state = SubagentChipState.OBSERVED))
        assertEquals(2, first.size())

        // Controller restart: brand new registry instance, same durable store.
        val restarted = registry(store)

        assertEquals(2, restarted.size())
        assertEquals(2, restarted.liveCount())
        val rehydrated = restarted.record("conv-a", "agent-1", "tool/1")
        assertNotNull(rehydrated)
        assertEquals("ship fix", rehydrated.description)
        assertEquals(SubagentChipState.RUNNING, rehydrated.state)
    }

    @Test
    fun restartThenReconcileAgainstLiveStateKeepsLiveAndOrphansTheRest() {
        val store = InMemorySubagentRegistryStore()
        val first = registry(store)
        first.observe(observation(toolCallId = "tool/alive"))
        first.observe(observation(toolCallId = "tool/dead"))

        val restarted = registry(store)
        // subagent.list from the source of truth only knows about tool/alive.
        val result = restarted.reconcile("conv-a", setOf("tool/alive"), generation = 7)

        assertEquals(1, result.liveRetained)
        assertEquals(listOf("tool/dead"), result.orphaned.map { it.toolCallId })
        assertEquals(
            SubagentChipState.RUNNING,
            restarted.record("conv-a", "agent-1", "tool/alive")?.state,
        )
        assertEquals(
            SubagentChipState.ORPHANED,
            restarted.record("conv-a", "agent-1", "tool/dead")?.state,
        )
        // Reconciliation is durable too: the next restart sees the orphan.
        assertEquals(
            SubagentChipState.ORPHANED,
            registry(store).record("conv-a", "agent-1", "tool/dead")?.state,
        )
    }

    // ---------------------------------- (b) two-actor isolation (or40x shape)

    @Test
    fun twoParentsChipsStayIsolated() {
        val store = InMemorySubagentRegistryStore()
        val reg = registry(store)
        reg.observe(observation(conversationId = "conv-a", agentId = "agent-a", toolCallId = "tool/1"))
        reg.observe(observation(conversationId = "conv-b", agentId = "agent-b", toolCallId = "tool/1"))

        assertEquals(2, reg.size())
        assertEquals(listOf("tool/1"), reg.snapshot("conv-a", includeTerminal = true).map { it.toolCallId })
        assertEquals(listOf("tool/1"), reg.snapshot("conv-b", includeTerminal = true).map { it.toolCallId })

        // Parent A completes and then reconciles away its chip; B is untouched.
        reg.observe(
            observation(
                conversationId = "conv-a",
                agentId = "agent-a",
                toolCallId = "tool/1",
                state = SubagentChipState.COMPLETED,
            ),
        )
        reg.reconcile("conv-a", emptySet())

        assertEquals(
            SubagentChipState.COMPLETED,
            reg.record("conv-a", "agent-a", "tool/1")?.state,
        )
        assertEquals(
            SubagentChipState.RUNNING,
            reg.record("conv-b", "agent-b", "tool/1")?.state,
        )
        assertEquals(1, reg.snapshot("conv-b", includeTerminal = false).size)
    }

    @Test
    fun reconcilingOneConversationNeverOrphansAnother() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation(conversationId = "conv-a", agentId = "agent-a", toolCallId = "t1"))
        reg.observe(observation(conversationId = "conv-b", agentId = "agent-b", toolCallId = "t2"))

        val result = reg.reconcile("conv-a", emptySet())

        assertEquals(listOf("t1"), result.orphaned.map { it.toolCallId })
        assertEquals(SubagentChipState.RUNNING, reg.record("conv-b", "agent-b", "t2")?.state)
    }

    // ------------------------------------------------- (c) orphan detection

    @Test
    fun persistedChipWithNoLiveCounterpartBecomesOrphanedNotDeleted() {
        val store = InMemorySubagentRegistryStore()
        val reg = registry(store)
        reg.observe(observation(toolCallId = "tool/ghost"))

        now = 5_000
        val result = reg.reconcile("conv-a", emptySet())

        assertEquals(1, result.orphaned.size)
        val ghost = reg.record("conv-a", "agent-1", "tool/ghost")
        assertNotNull(ghost, "an orphaned chip must never silently vanish")
        assertEquals(SubagentChipState.ORPHANED, ghost.state)
        assertEquals(5_000L, ghost.terminalAtEpochMs)
        // Terminal on the wire so clients stop rendering a forever-spinner.
        assertEquals(SubagentStatus.CANCELLED, ghost.toEntry().status)
        assertTrue(reg.snapshot("conv-a", includeTerminal = false).isEmpty())
    }

    @Test
    fun reconcileLeavesAlreadyTerminalChipsAlone() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation(toolCallId = "tool/done", state = SubagentChipState.COMPLETED))

        val result = reg.reconcile("conv-a", emptySet())

        assertTrue(result.orphaned.isEmpty())
        assertEquals(SubagentChipState.COMPLETED, reg.record("conv-a", "agent-1", "tool/done")?.state)
    }

    // --------------------------------------------- (d) source precedence 7vs4s

    @Test
    fun weakerSourceCannotOverwriteControllerNativeFact() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(
            observation(
                state = SubagentChipState.COMPLETED,
                source = SubagentChipSource.CONTROLLER_NATIVE,
            ),
        )

        val http = reg.observe(
            observation(state = SubagentChipState.RUNNING, source = SubagentChipSource.HTTP_REGISTRY),
        )
        val correlator = reg.observe(
            observation(state = SubagentChipState.RUNNING, source = SubagentChipSource.CORRELATOR_OBSERVED),
        )

        assertIs<DurableSubagentRegistry.ObserveResult.RejectedBySource>(http)
        assertIs<DurableSubagentRegistry.ObserveResult.RejectedBySource>(correlator)
        assertEquals(SubagentChipState.COMPLETED, reg.record("conv-a", "agent-1", "tool/1")?.state)
        assertEquals(
            SubagentChipSource.CONTROLLER_NATIVE,
            reg.record("conv-a", "agent-1", "tool/1")?.source,
        )
    }

    @Test
    fun strongerSourceOverridesWeakerAndPrecedenceIsOrderIndependent() {
        val forward = registry(InMemorySubagentRegistryStore())
        forward.observe(observation(source = SubagentChipSource.CORRELATOR_OBSERVED))
        forward.observe(
            observation(state = SubagentChipState.COMPLETED, source = SubagentChipSource.CONTROLLER_NATIVE),
        )

        val reverse = registry(InMemorySubagentRegistryStore())
        reverse.observe(
            observation(state = SubagentChipState.COMPLETED, source = SubagentChipSource.CONTROLLER_NATIVE),
        )
        reverse.observe(observation(source = SubagentChipSource.CORRELATOR_OBSERVED))

        // Same terminal outcome regardless of arrival order.
        assertEquals(SubagentChipState.COMPLETED, forward.record("conv-a", "agent-1", "tool/1")?.state)
        assertEquals(SubagentChipState.COMPLETED, reverse.record("conv-a", "agent-1", "tool/1")?.state)
    }

    @Test
    fun weakSourceMayStillCreateAChipTheControllerHasNotSeen() {
        val reg = registry(InMemorySubagentRegistryStore())

        val result = reg.observe(
            observation(toolCallId = "tool/new", source = SubagentChipSource.CORRELATOR_OBSERVED),
        )

        assertIs<DurableSubagentRegistry.ObserveResult.Accepted>(result)
        assertEquals(1, reg.liveCount())
    }

    @Test
    fun sourcePrecedenceIsATotalDocumentedOrder()  {
        assertTrue(
            SubagentChipSource.CORRELATOR_OBSERVED.precedence <
                SubagentChipSource.HTTP_REGISTRY.precedence,
        )
        assertTrue(
            SubagentChipSource.HTTP_REGISTRY.precedence <
                SubagentChipSource.CONTROLLER_NATIVE.precedence,
        )
    }

    // ------------------------------------------ (e) lifecycle mapping 5hihw

    @Test
    fun terminalChipCannotBeRewoundToRunning() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation(state = SubagentChipState.COMPLETED))

        val illegal = reg.observe(observation(state = SubagentChipState.RUNNING))

        assertIs<DurableSubagentRegistry.ObserveResult.IllegalTransition>(illegal)
        assertEquals(SubagentChipState.COMPLETED, reg.record("conv-a", "agent-1", "tool/1")?.state)
    }

    @Test
    fun orphanedChipCannotBeFlippedToCompleted() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation())
        reg.reconcile("conv-a", emptySet())

        val illegal = reg.observe(observation(state = SubagentChipState.COMPLETED))

        assertIs<DurableSubagentRegistry.ObserveResult.IllegalTransition>(illegal)
        assertEquals(SubagentChipState.ORPHANED, reg.record("conv-a", "agent-1", "tool/1")?.state)
    }

    @Test
    fun legalLifecycleEdgesAreAccepted() {
        val reg = registry(InMemorySubagentRegistryStore())
        assertIs<DurableSubagentRegistry.ObserveResult.Accepted>(
            reg.observe(observation(state = SubagentChipState.OBSERVED)),
        )
        assertIs<DurableSubagentRegistry.ObserveResult.Accepted>(
            reg.observe(observation(state = SubagentChipState.RUNNING)),
        )
        now = 9_000
        assertIs<DurableSubagentRegistry.ObserveResult.Accepted>(
            reg.observe(observation(state = SubagentChipState.FAILED)),
        )
        val record = reg.record("conv-a", "agent-1", "tool/1")
        assertEquals(SubagentChipState.FAILED, record?.state)
        assertEquals(9_000L, record?.terminalAtEpochMs)
    }

    @Test
    fun parentTurnEndingDoesNotCompleteBackgroundChips() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation(toolCallId = "task_2", state = SubagentChipState.OBSERVED))
        reg.observe(observation(toolCallId = "task_3", state = SubagentChipState.RUNNING))

        val retained = reg.markParentTurnEnded("conv-a")

        assertEquals(setOf("task_2", "task_3"), retained.map { it.toolCallId }.toSet())
        assertEquals(2, reg.liveCount())
        assertEquals(
            SubagentStatus.RUNNING,
            reg.record("conv-a", "agent-1", "task_2")?.toEntry()?.status,
        )
    }

    @Test
    fun unknownWireStatusMapsToObservedNeverToCompleted() {
        assertEquals(SubagentChipState.OBSERVED, SubagentChipState.fromWireStatus("wat"))
        assertEquals(SubagentChipState.OBSERVED, SubagentChipState.fromWireStatus(null))
        assertEquals(SubagentChipState.OBSERVED, SubagentChipState.fromWireStatus("pending"))
        assertEquals(SubagentChipState.COMPLETED, SubagentChipState.fromWireStatus("completed"))
        assertEquals(SubagentChipState.CANCELLED, SubagentChipState.fromWireStatus("cancelled"))
    }

    // ------------------------------------- (f) reconnect replay idempotence

    @Test
    fun replayAfterReconnectIsIdempotentByChipId() {
        val store = InMemorySubagentRegistryStore()
        val reg = registry(store)
        reg.observe(observation(toolCallId = "tool/1"))
        reg.observe(observation(toolCallId = "tool/2", state = SubagentChipState.COMPLETED))

        val firstReplay = reg.replaySnapshot("conv-a")
        // Reconnect: the same snapshot is folded back in, twice.
        repeat(2) {
            firstReplay.forEach { record ->
                reg.observe(
                    SubagentChipObservation.fromEntry(
                        entry = record.toEntry(),
                        conversationId = record.conversationId,
                        agentId = record.agentId,
                        source = SubagentChipSource.CONTROLLER_NATIVE,
                    ),
                )
            }
        }
        val secondReplay = reg.replaySnapshot("conv-a")

        assertEquals(2, reg.size())
        assertEquals(firstReplay.map { it.toolCallId }, secondReplay.map { it.toolCallId })
        assertEquals(secondReplay.size, secondReplay.map { it.toolCallId }.toSet().size)
        assertEquals(SubagentChipState.COMPLETED, reg.record("conv-a", "agent-1", "tool/2")?.state)
    }

    @Test
    fun replaySnapshotIncludesTerminalChipsSoTheyResurfaceAfterReconnect() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation(toolCallId = "tool/done", state = SubagentChipState.COMPLETED))

        assertEquals(listOf("tool/done"), reg.replaySnapshot("conv-a").map { it.toolCallId })
        assertTrue(reg.snapshot("conv-a", includeTerminal = false).isEmpty())
    }

    // ---------------------------------------------------- bounded persistence

    @Test
    fun boundedEvictionDropsOldestTerminalChipsOnly() {
        val store = InMemorySubagentRegistryStore()
        val reg = registry(store, maxEntries = 3)
        now = 100
        reg.observe(observation(toolCallId = "term/1", state = SubagentChipState.COMPLETED))
        now = 200
        reg.observe(observation(toolCallId = "term/2", state = SubagentChipState.COMPLETED))
        now = 300
        reg.observe(observation(toolCallId = "live/1"))
        now = 400
        reg.observe(observation(toolCallId = "live/2"))

        assertEquals(3, reg.size())
        assertNull(reg.record("conv-a", "agent-1", "term/1"), "oldest terminal is evicted first")
        assertNotNull(reg.record("conv-a", "agent-1", "term/2"))
        assertNotNull(reg.record("conv-a", "agent-1", "live/1"))
        assertNotNull(reg.record("conv-a", "agent-1", "live/2"))
        // Eviction is durable, not just in-memory.
        assertEquals(3, store.load().size)
    }

    @Test
    fun liveChipsAreNeverEvictedEvenOverCap() {
        val reg = registry(InMemorySubagentRegistryStore(), maxEntries = 2)
        repeat(5) { index ->
            now += 10
            reg.observe(observation(toolCallId = "live/$index"))
        }

        assertEquals(5, reg.size())
        assertEquals(5, reg.liveCount())
    }

    // -------------------------------------------------------------- plumbing

    @Test
    fun mergeBackfillsProvenanceWithoutClobbering() {
        val reg = registry(InMemorySubagentRegistryStore())
        reg.observe(observation(description = "ship fix"))
        reg.observe(
            SubagentChipObservation(
                conversationId = "conv-a",
                agentId = "agent-1",
                toolCallId = "tool/1",
                state = SubagentChipState.RUNNING,
                source = SubagentChipSource.CONTROLLER_NATIVE,
                description = "",
                taskId = "task-9",
                parentRunId = "run-3",
            ),
        )

        val record = reg.record("conv-a", "agent-1", "tool/1")
        assertEquals("ship fix", record?.description)
        assertEquals("task-9", record?.taskId)
        assertEquals("run-3", record?.parentRunId)
    }

    @Test
    fun corruptStoreDegradesToEmptyInsteadOfThrowing() {
        val exploding = object : SubagentRegistryStore {
            override fun load(): List<SubagentChipRecord> = error("corrupt")
            override fun save(records: List<SubagentChipRecord>) = Unit
        }

        val reg = DurableSubagentRegistry(store = exploding, clock = { now })

        assertEquals(0, reg.size())
    }
}
