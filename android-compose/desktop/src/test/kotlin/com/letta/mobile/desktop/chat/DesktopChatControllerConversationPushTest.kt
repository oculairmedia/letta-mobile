package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ConnectionStatusGateway
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.flow.MutableStateFlow
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-lks7m: a conversation started on another client (e.g. Android) reaches the desktop
 * list through Meridian's `conversation_updated` push, and merging it never disturbs what the user
 * is looking at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerConversationPushTest {

    /** A backend whose roster another client can grow while the desktop is running. */
    private class SharedRosterGateway(initial: List<String>) : FakeDesktopChatGateway(conversationIds = initial) {
        var roster: List<String> = initial
        var listCalls = 0

        override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
            listCalls += 1
            // Newest first, as the host lists them: the roster's first conversation is its latest.
            return roster.mapIndexed { index, id ->
                Conversation(
                    id = ConversationId(id),
                    agentId = AgentId("agent-0"),
                    summary = "Chat $id",
                    createdAt = "2026-06-07T01:00:00Z",
                    updatedAt = "2026-06-07T01:01:00Z",
                    lastMessageAt = "2026-06-07T01:02:${(59 - index).toString().padStart(2, '0')}Z",
                )
            }
        }
    }

    private class CountingGateway : FakeDesktopChatGateway(), ConnectionStatusGateway {
        override val connectionState = MutableStateFlow<ChannelTransportState>(
            ChannelTransportState.Connected("server", "session", "device"),
        )
        var rows = listOf(
            Conversation(ConversationId("target"), AgentId("agent-0"), summary = "Before"),
            Conversation(ConversationId("unchanged"), AgentId("agent-1"), summary = "Other agent"),
        )
        val requests = mutableListOf<AgentId>()
        override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> {
            requests += page.agent
            return rows.filter { it.agentId == page.agent }
        }
        override suspend fun getConversation(conversationId: String): Conversation = rows.first { it.id.value == conversationId }
    }

    private class PushingTransport : NoOpChannelTransport() {
        val pushes = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 16)
        override val events = pushes
    }

    private fun push(conversationId: String, reason: String = "created") = ServerFrame.ConversationUpdated(
        id = "conversation-updated-$conversationId",
        ts = "2026-09-25T21:00:00Z",
        conversationId = conversationId,
        agentId = "agent-0",
        reason = reason,
    )

    @Test
    fun conversationCreatedElsewhereAppearsWithoutMovingTheSelection() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1", "conv-2"))
        val transport = PushingTransport()
        val controller = testController(gateway, transport)
        controller.start()
        runCurrent()
        controller.selectConversation("conv-2")
        runCurrent()
        controller.updateComposerText("draft in progress")
        val generationBefore = controller.state.value.selectionGeneration
        val hydratesBefore = gateway.conversationMessageRequests.size

        // Another client starts a new chat; it sorts to the top of the server's roster.
        gateway.roster = listOf("conv-mobile", "conv-1", "conv-2")
        transport.pushes.emit(push("conv-mobile"))
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf("conv-mobile", "conv-1", "conv-2"), state.conversations.map { it.id })
        assertEquals("conv-2", state.selectedConversationId, "a pushed refresh must not change the selection")
        assertEquals(generationBefore, state.selectionGeneration, "the open timeline must not be rebound")
        assertEquals("draft in progress", state.composerText, "the composer draft must survive a refresh")
        assertEquals(
            hydratesBefore, gateway.conversationMessageRequests.size,
            "merging the roster must not re-hydrate the selected conversation",
        )
        controller.close()
    }

    @Test
    fun withoutAPushTheRosterIsNotReRead() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1"))
        val controller = testController(gateway, PushingTransport())
        controller.start()
        runCurrent()
        val callsAfterLoad = gateway.listCalls

        gateway.roster = listOf("conv-new", "conv-1")
        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals(callsAfterLoad, gateway.listCalls, "nothing polls: only a push or reconnect re-reads the roster")
        controller.close()
    }

    @Test
    fun aBurstOfPushesNeverStacksUpReads() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1"))
        val transport = PushingTransport()
        val controller = testController(gateway, transport)
        controller.start()
        runCurrent()
        val callsBefore = gateway.listCalls

        repeat(10) { transport.pushes.tryEmit(push("conv-$it")) }
        runCurrent()

        // One read serves the first push; the rest collapse into at most one follow-up.
        val extraReads = gateway.listCalls - callsBefore
        assertEquals(true, extraReads in 1..2, "expected 1-2 roster reads for a burst, got $extraReads")
        controller.close()
    }

    @Test
    fun emptyRosterOpensTheFirstConversationThatArrives() = runTest {
        val gateway = SharedRosterGateway(emptyList())
        val transport = PushingTransport()
        val controller = testController(gateway, transport)
        controller.start()
        runCurrent()
        assertEquals(DesktopChatConnectionState.NoConversations, controller.state.value.connectionState)

        gateway.roster = listOf("conv-mobile")
        transport.pushes.emit(push("conv-mobile"))
        runCurrent()

        assertEquals("conv-mobile", controller.state.value.selectedConversationId)
        assertEquals("Agent 0", controller.state.value.conversations.single().agentName)
        assertEquals(DesktopChatConnectionState.Live, controller.state.value.connectionState)
        controller.close()
    }

    @Test
    fun aClosedControllerIgnoresPushes() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1"))
        val transport = PushingTransport()
        val controller = testController(gateway, transport)
        controller.start()
        runCurrent()
        controller.close()
        val callsAtClose = gateway.listCalls

        transport.pushes.tryEmit(push("conv-late"))
        runCurrent()

        assertEquals(callsAtClose, gateway.listCalls)
    }

    @Test
    fun onePushRefreshesOnlyItsAgentAndPreservesSelectionAndDraft() = runTest {
        val gateway = CountingGateway()
        val transport = PushingTransport()
        var rosterCalls = 0
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(), scope = backgroundScope,
            rosterProvider = {
                rosterCalls++
                listOf(Agent(AgentId("agent-0"), name = "Zero"), Agent(AgentId("agent-1"), name = "One"))
            },
            gatewayFactory = { gateway }, conversationChanges = transport,
        )
        controller.start()
        runCurrent()
        controller.selectConversation("unchanged")
        runCurrent()
        controller.updateComposerText("keep draft")
        val generation = controller.state.value.selectionGeneration
        val rosterBefore = rosterCalls
        gateway.requests.clear()
        gateway.rows = gateway.rows.map { if (it.id.value == "target") it.copy(summary = "Updated") else it }

        transport.pushes.emit(push("target"))
        runCurrent()

        val afterPush = gateway.requests.toList()
        val stateAfterPush = controller.state.value
        val rosterAfterPush = rosterCalls
        println(
            "one-push dispatches target=${afterPush.count { it.value == "agent-0" }} " +
                "unchanged=${afterPush.count { it.value == "agent-1" }} roster=${rosterAfterPush - rosterBefore}",
        )
        gateway.requests.clear()
        repeat(10) { transport.pushes.tryEmit(push("target-$it")) }
        runCurrent()
        val afterBurst = gateway.requests.toList()
        println("burst N=10 dispatches=${afterBurst.map { it.value }}")

        gateway.requests.clear()
        gateway.connectionState.value = ChannelTransportState.Disconnected(1006, "offline")
        runCurrent()
        gateway.connectionState.value = ChannelTransportState.Connected("server", "session-2", "device")
        runCurrent()
        println("reconnect dispatches=${gateway.requests.map { it.value }}")

        assertEquals(0, afterPush.count { it.value == "agent-1" }, "an unchanged agent must get zero list requests")
        assertEquals(listOf(AgentId("agent-0")), afterPush)
        assertEquals(rosterBefore, rosterAfterPush)
        assertEquals("Updated", stateAfterPush.conversations.first { it.id == "target" }.title)
        assertEquals("unchanged", stateAfterPush.selectedConversationId)
        assertEquals(generation, stateAfterPush.selectionGeneration)
        assertEquals("keep draft", stateAfterPush.composerText)
        assertEquals(listOf(AgentId("agent-0")), afterBurst, "one owning-agent read serves the entire queued burst")
        assertEquals(setOf(AgentId("agent-0"), AgentId("agent-1")), gateway.requests.toSet())
        assertEquals(2, gateway.requests.size, "reconnect reconciles both agents")
        controller.close()
    }

    private fun TestScope.testController(gateway: DesktopChatGateway, transport: PushingTransport): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            // Every conversation here is agent-0's; one agent keeps one read per roster refresh.
            rosterProvider = { listOf(Agent(id = AgentId("agent-0"), name = "Agent 0")) },
            gatewayFactory = { gateway },
            conversationChanges = transport,
        )
}
