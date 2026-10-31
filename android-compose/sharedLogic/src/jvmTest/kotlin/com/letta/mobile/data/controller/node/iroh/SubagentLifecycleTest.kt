@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.subagents.SubagentChipState
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the production host subscription with real registry and store implementations. */
class SubagentLifecycleTest {
    private var now = 1_000L
    private val store = InMemorySubagentRegistryStore()
    private val source = ControllerSubagentRegistrySource(DurableSubagentRegistry(store, clock = { now }))
    private val changes = mutableListOf<SubagentConversationKey>()

    init {
        source.changeListener = SubagentRegistryChangeListener { changes += it }
    }

    /** The owned scheduler expires idle chips and stops when its subscription is cancelled. */
    @Test
    fun idleHostExpiresPersistsAndPublishesWithoutAnotherFrameOrRestart() = runTest {
        seed()
        val job = source.start(backgroundScope, MutableSharedFlow<AppServerReceivedFrame>())
        runCurrent()
        changes.clear()
        now += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS + 1

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(SubagentChipState.CANCELLED, record().state, "the idle host must sweep on its timer")
        assertEquals("stale", store.load().single().terminalReason)
        assertEquals(listOf(SubagentConversationKey("parent", "conv")), changes)
        job.cancel()
        seed("after-stop")
        now += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS + 1
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertEquals(SubagentChipState.RUNNING, source.registry.findByToolCall("conv", "after-stop")?.state)
    }

    /** Confirmed absence and silence retire a worker that was previously visible in snapshots. */
    @Test
    fun repeatedAbsenceAndSilenceOrphanPersistAndPublishAfterThirtyMinutes() {
        snapshot("call")
        now += 1
        snapshot()
        changes.clear()
        now += ABSENCE_MS

        snapshot()

        assertEquals(SubagentChipState.ORPHANED, record().state, "bounded absence must retire a dead worker")
        assertEquals(SubagentChipState.ORPHANED, store.load().single().state)
        assertEquals(listOf(SubagentConversationKey("parent", "conv")), changes)
    }

    /** An old observation cannot turn the first omission into terminal evidence. */
    @Test
    fun oneOmissionDoesNotOrphanEvenWhenLastSeenIsOld() {
        snapshot("call")
        now += ABSENCE_MS + 1
        snapshot()
        assertEquals(SubagentChipState.RUNNING, record().state)
    }

    /** A recent accepted observation renews the silence grace period. */
    @Test
    fun absentButRecentlyObservedWorkerIsRetained() {
        snapshot("call")
        snapshot()
        now += ABSENCE_MS
        seed()
        snapshot()
        assertEquals(SubagentChipState.RUNNING, record().state)
    }

    /** Snapshot presence retains a silent worker and restarts the absence grace period. */
    @Test
    fun presentButSilentWorkerIsRetainedAndResetsAbsence() {
        snapshot("call")
        val activityAt = record().lastActivityEpochMs
        snapshot()
        now += ABSENCE_MS + 1
        snapshot("call")
        assertEquals(SubagentChipState.RUNNING, record().state)
        assertEquals(activityAt, record().lastActivityEpochMs)
        now += ABSENCE_MS - 60_000L
        snapshot()
        now += 60_000L
        snapshot()
        assertEquals(SubagentChipState.RUNNING, record().state, "reappearance must reset the absence clock")
        now += ABSENCE_MS - 60_000L
        snapshot()
        assertEquals(SubagentChipState.ORPHANED, record().state)
    }

    /** Only the exact parent and conversation can supply absence evidence. */
    @Test
    fun omissionsAreScopedToExactParentAndConversation() {
        snapshot("call")
        now += ABSENCE_MS + 1
        snapshot(agent = "other-parent")
        snapshot(conversation = "other-conversation")
        now += ABSENCE_MS + 1
        snapshot(agent = "other-parent")
        snapshot(conversation = "other-conversation")
        assertEquals(SubagentChipState.RUNNING, record().state)
    }

    /** Unknown visibility and a restarted host retain the conservative TTL fallback. */
    @Test
    fun streamOnlyAndRehydratedChipsNeedSnapshotVisibilityBeforeShortOrphaning() {
        seed()
        snapshot()
        now += ABSENCE_MS + 1
        snapshot()
        assertEquals(SubagentChipState.RUNNING, record().state)
        snapshot("call")
        snapshot()
        now += ABSENCE_MS / 2
        val restarted = ControllerSubagentRegistrySource(DurableSubagentRegistry(store, clock = { now }))
        restarted.ingest(frame(emptyList()))
        now += ABSENCE_MS + 1
        restarted.ingest(frame(emptyList()))
        assertEquals(SubagentChipState.RUNNING, restarted.registry.findByToolCall("conv", "call")?.state)
    }

    /** Maintenance visibility policy leaves the host registry and its persisted records intact. */
    @Test
    fun maintenanceEntriesRemainAvailableInTheHostRegistry() = runTest {
        source.ingestFromSource(
            "conv", "parent",
            listOf(SubagentEntry(toolCallId = "reflection", subagentType = "Reflection integration", status = "running")),
            SubagentChipSource.CONTROLLER_NATIVE,
        )
        assertEquals("Reflection integration", source.list("conv", true).single().subagentType)
        assertEquals("Reflection integration", store.load().single().subagentType)
    }

    private fun seed(id: String = "call") = source.ingestFromSource(
        "conv", "parent", listOf(SubagentEntry(toolCallId = id, status = "running")),
        SubagentChipSource.CONTROLLER_NATIVE,
    )

    // Real buildSubagentSnapshot filters exact parent scope and emits these snake_case keys.
    private fun snapshot(vararg ids: String, agent: String = "parent", conversation: String = "conv") =
        source.ingestReceived(AppServerProtocol.decodeFrame(wireFrame(ids.toList(), agent, conversation)))

    private fun frame(ids: List<String>) =
        AppServerProtocol.decodeFrame(wireFrame(ids, "parent", "conv")).frame

    private fun wireFrame(ids: List<String>, agent: String, conversation: String): String = buildJsonObject {
        put("type", "update_subagent_state")
        put("runtime", buildJsonObject {
            put("agent_id", agent)
            put("conversation_id", conversation)
        })
        put("event_seq", now)
        put("emitted_at", "2026-10-30T23:00:00Z")
        put("idempotency_key", "$now:$agent:$conversation:$ids")
        put("subagents", JsonArray(ids.map { id ->
            buildJsonObject {
                put("subagent_id", "subagent-$id")
                put("tool_call_id", id)
                put("subagent_type", "General-purpose")
                put("description", "Inspect worker")
                put("status", "running")
                put("agent_url", JsonNull)
                put("conversation_id", JsonNull)
                put("start_time", 1_791_677_000_000L)
                put("tool_calls", JsonArray(emptyList()))
                put("total_tokens", 0)
                put("duration_ms", 0)
                put("parent_agent_id", agent)
                put("parent_conversation_id", conversation)
                put("is_background", true)
                put("silent", false)
            }
        }))
    }.toString()

    private fun record() = requireNotNull(source.registry.findByToolCall("conv", "call"))

    private companion object {
        const val ABSENCE_MS = 30 * 60_000L
    }
}
