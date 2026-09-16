package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class AgentChangeNotifierTest {
    private val sent = mutableListOf<String>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun TestScope.notifier() = AgentChangeNotifier(backgroundScope, windowMs = 250, clock = { Instant.parse("2026-09-15T21:00:00Z") })
        .also { it.attach { frame -> sent += frame } }

    private fun decoded(): List<ServerFrame.AgentUpdated> = sent.map { assertIs<ServerFrame.AgentUpdated>(json.decodeFromString(ServerFrameSerializer, it)) }

    @Test
    fun framesAreTheAgentUpdatedFrameClientsAlreadyDecode() = runTest {
        val notifier = notifier()
        notifier.notify("agent-1", AgentChangeKind.Updated)
        advanceTimeBy(251)
        runCurrent()

        val frame = decoded().single()
        assertEquals("agent-1", frame.agentId)
        assertEquals("updated", frame.reason)
        assertEquals("2026-09-15T21:00:00Z", frame.ts)
        assertTrue(frame.id.startsWith("agent-updated-"))
    }

    @Test
    fun aBurstForOneAgentWithinTheWindowIsOneFrame() = runTest {
        val notifier = notifier()
        repeat(5) { notifier.notify("agent-1", AgentChangeKind.Updated) }
        notifier.notify("agent-2", AgentChangeKind.Updated)

        advanceTimeBy(200)
        runCurrent()
        assertEquals(0, sent.size, "nothing leaves before the window closes")

        advanceTimeBy(51)
        runCurrent()
        assertEquals(listOf("agent-1", "agent-2"), decoded().map { it.agentId })
    }

    @Test
    fun theNetChangeWinsWithinAWindow() {
        assertEquals(AgentChangeKind.Created, AgentChangeNotifier.merge(AgentChangeKind.Created, AgentChangeKind.Updated))
        assertEquals(AgentChangeKind.Deleted, AgentChangeNotifier.merge(AgentChangeKind.Created, AgentChangeKind.Deleted))
        assertEquals(AgentChangeKind.Deleted, AgentChangeNotifier.merge(AgentChangeKind.Updated, AgentChangeKind.Deleted))
        assertEquals(AgentChangeKind.Updated, AgentChangeNotifier.merge(AgentChangeKind.Deleted, AgentChangeKind.Created))
    }

    @Test
    fun aChangeAfterAFlushStartsANewWindow() = runTest {
        val notifier = notifier()
        notifier.notify("agent-1", AgentChangeKind.Created)
        advanceTimeBy(251)
        runCurrent()
        notifier.notify("agent-1", AgentChangeKind.Deleted)
        advanceTimeBy(251)
        runCurrent()

        assertEquals(listOf("created", "deleted"), decoded().map { it.reason })
    }

    @Test
    fun withoutATargetChangesAreDroppedNotQueued() = runTest {
        val notifier = AgentChangeNotifier(backgroundScope, windowMs = 250)
        notifier.notify("agent-1", AgentChangeKind.Updated)
        advanceTimeBy(251)
        runCurrent()
        notifier.attach { frame -> sent += frame }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, sent.size, "a late-attached target must not receive a stale backlog")
    }
}
