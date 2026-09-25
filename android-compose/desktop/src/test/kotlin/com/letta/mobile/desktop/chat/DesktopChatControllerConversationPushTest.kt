package com.letta.mobile.desktop.chat

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
            return roster.map { id ->
                Conversation(
                    id = ConversationId(id),
                    agentId = AgentId("agent-0"),
                    summary = "Chat $id",
                    createdAt = "2026-06-07T01:00:00Z",
                    updatedAt = "2026-06-07T01:01:00Z",
                    lastMessageAt = "2026-06-07T01:02:00Z",
                )
            }
        }
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

    private fun TestScope.testController(gateway: DesktopChatGateway, transport: PushingTransport): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
            timelinePersistence = noOpDesktopTimelinePersistence,
            conversationChanges = transport,
        )
}
