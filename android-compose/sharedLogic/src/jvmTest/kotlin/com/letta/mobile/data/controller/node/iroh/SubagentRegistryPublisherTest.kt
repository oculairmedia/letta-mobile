package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-fxoew.2 / .3: the host pushes its registry to conversation
 * viewers, replays it once to a new viewer, and ends stale chips without
 * ending fresh background work at a parent turn end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentRegistryPublisherTest {
    private var nowMs = 10_000_000L
    private val registry = DurableSubagentRegistry(store = InMemorySubagentRegistryStore(), clock = { nowMs })
    private val source = ControllerSubagentRegistrySource(registry)
    private val decoder = Json { ignoreUnknownKeys = true }

    private fun chip(toolCallId: String, status: String): JsonObject = buildJsonObject {
        put("toolCallId", toolCallId)
        put("description", "work $toolCallId")
        put("status", status)
        put("parentConversationId", CONVERSATION)
        put("parentAgentId", AGENT)
    }

    private fun stateFrame(seq: Long, vararg chips: JsonObject) = AppServerInboundFrame.UpdateSubagentState(
        runtime = AppServerRuntimeScope(agentId = AGENT, conversationId = CONVERSATION),
        eventSeq = seq,
        emittedAt = "t",
        idempotencyKey = "k-$seq",
        subagents = chips.toList(),
    )

    private fun decode(frame: String): ServerFrame.SubagentsUpdated =
        decoder.decodeFromString(ServerFrameSerializer, frame) as ServerFrame.SubagentsUpdated

    private class RecordingViewer(override val connectionId: String) : ViewerHandle {
        val frames = CopyOnWriteArrayList<String>()
        override suspend fun writeFrame(frame: String): Boolean = frames.add(frame)
    }

    @Test
    fun registryMutationPublishesFullSnapshotIncludingCompleted() = runTest {
        val published = CopyOnWriteArrayList<Pair<String, String>>()
        val publisher = SubagentRegistryPublisher(backgroundScope, source, clock = { Instant.ofEpochMilli(nowMs) })
        publisher.attach { conversationId, frame -> published += conversationId to frame; 1 }
        source.changeListener = publisher

        source.ingest(stateFrame(1, chip("tool-run", "running"), chip("tool-done", "success")))
        runCurrent()

        val (conversationId, frame) = published.single()
        assertEquals(CONVERSATION, conversationId)
        val pushed = decode(frame)
        assertEquals(
            mapOf("tool-run" to "running", "tool-done" to "completed"),
            pushed.subagentsActive.associate { it.toolCallId to it.status },
        )
        assertTrue(pushed.subagentsActive.all { it.parentConversationId == CONVERSATION && it.parentAgentId == AGENT })
        publisher.close()
    }

    @Test
    fun terminalChipLeavesPushedSnapshotAfterLinger() = runTest {
        source.ingest(stateFrame(1, chip("tool-done", "success")))
        nowMs += ControllerSubagentRegistrySource.PUSH_TERMINAL_LINGER_MS
        assertEquals(emptyList(), source.pushSnapshot(SubagentConversationKey(AGENT, CONVERSATION), nowMs))
    }

    @Test
    fun viewerJoinReplaysExactlyOneSnapshot() = runTest {
        val publisher = SubagentRegistryPublisher(backgroundScope, source, clock = { Instant.ofEpochMilli(nowMs) })
        source.ingest(stateFrame(1, chip("tool-run", "running")))
        val connections = ConnectionRegistry()
        connections.addViewerJoinedListener { conversationId, viewer -> publisher.replayTo(conversationId, viewer) }
        val viewer = RecordingViewer("endpoint-1")
        val registration = connections.claim(viewer)

        connections.register(CONVERSATION, registration)
        // A re-registration (message.list paging) is not a new join.
        connections.register(CONVERSATION, registration)

        val replayed = viewer.frames.single()
        assertEquals(listOf("tool-run"), decode(replayed).subagentsActive.map { it.toolCallId })
        assertEquals(SubagentRegistryPublisher.REASON_REPLAY, decode(replayed).reason)
        publisher.close()
    }

    @Test
    fun parentTurnEndKeepsFreshBackgroundChipRunning() = runTest {
        source.ingest(stateFrame(1, chip("tool-bg", "running")))
        nowMs += 60_000L

        source.ingest(turnFinished())

        assertEquals(SubagentChipState.RUNNING, registry.findByToolCall(CONVERSATION, "tool-bg")?.state)
    }

    @Test
    fun parentTurnEndEndsChipUnobservedPastTheTtl() = runTest {
        val changed = CopyOnWriteArrayList<String>()
        source.changeListener = SubagentRegistryChangeListener { key -> changed += key.conversationId }
        source.ingest(stateFrame(1, chip("tool-zombie", "running")))
        changed.clear()
        nowMs += DurableSubagentRegistry.STALE_RUNNING_AFTER_MS + 1

        source.ingest(turnFinished())

        val record = registry.findByToolCall(CONVERSATION, "tool-zombie")
        assertEquals(SubagentChipState.CANCELLED, record?.state)
        assertEquals("stale", record?.terminalReason)
        assertEquals(listOf(CONVERSATION), changed)
    }

    @Test
    fun pushCapabilityIsAdvertisedOnlyWhenTheRouterEnablesIt() {
        val router = AdminRpcRouter()
        assertTrue(ControllerSubagentRegistrySource.PUSH_CAPABILITY !in IrohNodeConnection.advertisedCapabilities(router))
        router.featureCapabilities += ControllerSubagentRegistrySource.PUSH_CAPABILITY
        val copied = AdminRpcRouter().apply {
            router.register("noop") { kotlinx.serialization.json.JsonNull }
            copyHandlersFrom(router)
        }
        assertTrue(ControllerSubagentRegistrySource.PUSH_CAPABILITY in IrohNodeConnection.advertisedCapabilities(copied))
    }

    private fun turnFinished() = AppServerInboundFrame.TurnFinished(
        runtime = AppServerRuntimeScope(agentId = AGENT, conversationId = CONVERSATION),
        eventSeq = 9,
        emittedAt = "t",
        idempotencyKey = "turn-9",
        turnId = "turn-9",
        stopReason = "end_turn",
    )

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-a"
    }
}
