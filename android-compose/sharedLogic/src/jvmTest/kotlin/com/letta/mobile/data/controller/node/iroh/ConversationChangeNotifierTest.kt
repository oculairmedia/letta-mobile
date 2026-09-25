package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationChangeNotifierTest {
    private val sent = mutableListOf<String>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun TestScope.notifier() =
        ConversationChangeNotifier(backgroundScope, windowMs = 250, clock = { Instant.parse("2026-09-25T21:00:00Z") })
            .also { it.attach { frame -> sent += frame } }

    private fun decoded(): List<ServerFrame.ConversationUpdated> =
        sent.map { assertIs<ServerFrame.ConversationUpdated>(json.decodeFromString(ServerFrameSerializer, it)) }

    @Test
    fun framesAreTheConversationUpdatedFrameClientsDecode() = runTest {
        val notifier = notifier()
        notifier.notify("conv-1", "agent-1", ConversationChangeKind.Created)
        advanceTimeBy(251)
        runCurrent()

        val frame = decoded().single()
        assertEquals("conv-1", frame.conversationId)
        assertEquals("agent-1", frame.agentId)
        assertEquals("created", frame.reason)
        assertEquals("2026-09-25T21:00:00Z", frame.ts)
        assertTrue(frame.id.startsWith("conversation-updated-"))
    }

    @Test
    fun aBurstForOneConversationWithinTheWindowIsOneFrame() = runTest {
        val notifier = notifier()
        repeat(5) { notifier.notify("conv-1", "agent-1", ConversationChangeKind.Updated) }
        notifier.notify("conv-2", null, ConversationChangeKind.Archived)

        advanceTimeBy(200)
        runCurrent()
        assertEquals(0, sent.size, "nothing leaves before the window closes")

        advanceTimeBy(51)
        runCurrent()
        assertEquals(listOf("conv-1" to "updated", "conv-2" to "archived"), decoded().map { it.conversationId to it.reason })
        assertNull(decoded().last().agentId, "an unknown owner is omitted, not invented")
    }

    @Test
    fun aKnownOwnerSurvivesALaterChangeThatLacksIt() = runTest {
        val notifier = notifier()
        notifier.notify("conv-1", "agent-1", ConversationChangeKind.Created)
        notifier.notify("conv-1", null, ConversationChangeKind.Updated)
        advanceTimeBy(251)
        runCurrent()

        val frame = decoded().single()
        assertEquals("agent-1", frame.agentId)
        assertEquals("created", frame.reason)
    }

    @Test
    fun aCreationStaysACreationWithinAWindow() {
        assertEquals(ConversationChangeKind.Created, ConversationChangeNotifier.merge(ConversationChangeKind.Created, ConversationChangeKind.Updated))
        assertEquals(ConversationChangeKind.Created, ConversationChangeNotifier.merge(ConversationChangeKind.Created, ConversationChangeKind.Archived))
        assertEquals(ConversationChangeKind.Archived, ConversationChangeNotifier.merge(ConversationChangeKind.Updated, ConversationChangeKind.Archived))
        assertEquals(ConversationChangeKind.Restored, ConversationChangeNotifier.merge(ConversationChangeKind.Archived, ConversationChangeKind.Restored))
    }

    @Test
    fun withoutATargetChangesAreDroppedNotQueued() = runTest {
        val notifier = ConversationChangeNotifier(backgroundScope, windowMs = 250)
        notifier.notify("conv-1", "agent-1", ConversationChangeKind.Created)
        advanceTimeBy(251)
        runCurrent()
        notifier.attach { frame -> sent += frame }
        advanceTimeBy(251)
        runCurrent()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun aBlankConversationIdIsIgnored() = runTest {
        val notifier = notifier()
        notifier.notify(" ", "agent-1", ConversationChangeKind.Created)
        advanceTimeBy(251)
        runCurrent()

        assertTrue(sent.isEmpty())
    }
}
