package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-lks7m: a conversation started on another client (e.g. Android) must reach the
 * desktop roster without a restart, and the background re-read must never disturb what the user
 * is looking at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerRosterRefreshTest {

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

    @Test
    fun conversationCreatedElsewhereAppearsWithoutMovingTheSelection() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1", "conv-2"))
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        controller.selectConversation("conv-2")
        runCurrent()
        controller.updateComposerText("draft in progress")
        val generationBefore = controller.state.value.selectionGeneration
        val hydratesBefore = gateway.conversationMessageRequests.size

        // Another client starts a new chat; it sorts to the top of the server's roster.
        gateway.roster = listOf("conv-mobile", "conv-1", "conv-2")
        advanceTimeBy(INTERVAL + DEBOUNCE + 1.milliseconds)
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf("conv-mobile", "conv-1", "conv-2"), state.conversations.map { it.id })
        assertEquals("conv-2", state.selectedConversationId, "a background refresh must not change the selection")
        assertEquals(generationBefore, state.selectionGeneration, "the open timeline must not be rebound")
        assertEquals("draft in progress", state.composerText, "the composer draft must survive a refresh")
        assertEquals(
            hydratesBefore, gateway.conversationMessageRequests.size,
            "merging the roster must not re-hydrate the selected conversation",
        )
        controller.close()
    }

    @Test
    fun unfocusedWindowIsNotPolledAndRefocusRefreshesImmediately() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1"))
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        controller.onWindowFocusChanged(false)
        val callsWhileHidden = gateway.listCalls

        gateway.roster = listOf("conv-new", "conv-1")
        advanceTimeBy(INTERVAL * 5)
        runCurrent()
        assertEquals(callsWhileHidden, gateway.listCalls, "an unfocused window must not poll")

        controller.onWindowFocusChanged(true)
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        runCurrent()

        assertEquals(callsWhileHidden + 1, gateway.listCalls)
        assertTrue(controller.state.value.conversations.any { it.id == "conv-new" })
        assertEquals("conv-1", controller.state.value.selectedConversationId)
        controller.close()
    }

    @Test
    fun burstOfRefreshRequestsReadsTheRosterOnce() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1"))
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        val callsBefore = gateway.listCalls

        repeat(10) { controller.requestConversationRosterRefresh() }
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        runCurrent()

        assertEquals(callsBefore + 1, gateway.listCalls)
        controller.close()
    }

    @Test
    fun emptyRosterOpensTheFirstConversationThatAppears() = runTest {
        val gateway = SharedRosterGateway(emptyList())
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        assertEquals(DesktopChatConnectionState.NoConversations, controller.state.value.connectionState)

        gateway.roster = listOf("conv-mobile")
        controller.requestConversationRosterRefresh()
        advanceTimeBy(DEBOUNCE + 1.milliseconds)
        runCurrent()

        assertEquals("conv-mobile", controller.state.value.selectedConversationId)
        assertEquals(DesktopChatConnectionState.Live, controller.state.value.connectionState)
        controller.close()
    }

    @Test
    fun closedControllerStopsPolling() = runTest {
        val gateway = SharedRosterGateway(listOf("conv-1"))
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        controller.close()
        val callsAtClose = gateway.listCalls

        advanceTimeBy(INTERVAL * 5)
        runCurrent()

        assertEquals(callsAtClose, gateway.listCalls)
    }

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
            timelinePersistence = noOpDesktopTimelinePersistence,
            rosterRefreshInterval = INTERVAL,
            rosterRefreshDebounce = DEBOUNCE,
        )

    private companion object {
        val INTERVAL = 30.seconds
        val DEBOUNCE = 500.milliseconds
    }
}
