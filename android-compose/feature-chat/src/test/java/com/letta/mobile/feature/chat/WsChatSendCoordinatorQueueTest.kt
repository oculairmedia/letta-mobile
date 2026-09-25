package com.letta.mobile.feature.chat

import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsConnectionState
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.feature.chat.coordination.ChatClientVersionProvider
import com.letta.mobile.feature.chat.coordination.WsChatSendCoordinator
import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import com.letta.mobile.ui.chat.render.ChatUiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-1n5py: sends during a busy turn queue behind it, through the Android binding. */
@OptIn(ExperimentalCoroutinesApi::class)
class WsChatSendCoordinatorQueueTest {

    @Test
    fun `busy send is queued without a row and runs after turn done`() = runTest {
        val rig = QueueRig(this, BridgeScript(sendResults = listOf(false, true)))

        rig.coordinator.send("hello").join()
        runCurrent()

        assertNull(rig.uiState.value.error)
        assertTrue(rig.composerCleared)
        assertTrue(rig.uiState.value.isStreaming)
        assertTrue(rig.timeline.externalLocals.isEmpty())
        assertEquals(listOf("hello"), rig.visibleQueueTexts())

        rig.finishTurn(BridgeTurnStatus.Completed)

        rig.verifySent("hello", times = 2)
        assertEquals("hello", rig.timeline.externalLocals.single().content)
        assertTrue(rig.uiState.value.sendQueue.isEmpty)
    }

    @Test
    fun `busy sends queue without a limit and never raise an error`() = runTest {
        val rig = QueueRig(this, BridgeScript(sendResults = listOf(false)))

        repeat(25) { index -> rig.coordinator.send("message-$index").join() }
        runCurrent()

        assertNull(rig.uiState.value.error)
        assertEquals((0 until 25).map { "message-$it" }, rig.visibleQueueTexts())
        assertTrue(rig.timeline.externalLocals.isEmpty())
    }

    @Test
    fun `disconnect pauses queued sends instead of dropping them`() = runTest {
        val rig = QueueRig(this, BridgeScript(sendResults = listOf(false)))
        rig.coordinator.send("one").join()
        rig.coordinator.send("two").join()

        rig.coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "network lost"))
        advanceUntilIdle()
        runCurrent()

        assertEquals("network lost", rig.uiState.value.error)
        assertEquals(false, rig.uiState.value.isStreaming)
        assertTrue(rig.timeline.failedLocals.isEmpty())
        assertTrue(rig.uiState.value.sendQueue.paused)
        assertEquals(listOf("one", "two"), rig.visibleQueueTexts())
    }

    @Test
    fun `stop pauses only the active conversation queue`() = runTest {
        val rig = QueueRig(this, BridgeScript(sendResults = listOf(false)), activeConversation = "conv-a")
        rig.coordinator.send("one").join()
        rig.activeConversation = "conv-b"
        rig.coordinator.send("two").join()

        rig.activeConversation = "conv-a"
        assertTrue(rig.coordinator.cancel())
        advanceUntilIdle()

        verify(exactly = 1) { rig.bridge.cancel("conv-a") }
        assertEquals(true, rig.queueOf("conv-a")?.paused)
        assertEquals(false, rig.queueOf("conv-b")?.paused)
        assertTrue(rig.timeline.failedLocals.isEmpty())
    }

    @Test
    fun `a rejected stop leaves the queue running into the next turn`() = runTest {
        val rig = QueueRig(
            this,
            BridgeScript(sendResults = listOf(false, true), cancelResult = false),
            activeConversation = "conv-a",
        )
        rig.coordinator.send("one").join()

        assertEquals(false, rig.coordinator.cancel())
        advanceUntilIdle()
        assertEquals(false, rig.queueOf("conv-a")?.paused)

        rig.finishTurn(BridgeTurnStatus.Completed)

        rig.verifySent("one", times = 2, conversationId = "conv-a")
    }

    @Test
    fun `send now aborts the running turn and runs the chosen message first`() = runTest {
        val rig = QueueRig(this, BridgeScript(sendResults = listOf(false)))
        rig.coordinator.send("first").join()
        rig.coordinator.send("second").join()
        runCurrent()
        val second = rig.uiState.value.sendQueue.items.last()

        rig.coordinator.sendQueue.sendNow(second.id).join()
        runCurrent()
        verify(exactly = 1) { rig.bridge.cancel("conv-1") }
        assertEquals(listOf("second", "first"), rig.visibleQueueTexts())

        every { rig.bridge.send(any(), any(), any(), any(), any(), any()) } returns true
        rig.finishTurn(BridgeTurnStatus.Cancelled)

        assertEquals("second", rig.timeline.externalLocals.single().content)
        assertEquals(listOf("first"), rig.visibleQueueTexts())
    }

    /** How the fake bridge answers: send acceptance in order, and whether a Stop is accepted. */
    private data class BridgeScript(
        val sendResults: List<Boolean>,
        val cancelResult: Boolean = true,
    )

    /** One coordinator over a scripted bridge whose turn stays live until [finishTurn]. */
    private class QueueRig(
        private val scope: TestScope,
        script: BridgeScript,
        var activeConversation: String = "conv-1",
    ) {
        var busy = true
        var composerCleared = false
        val timeline = FakeTimelineExternalTransportWriter()
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val bridge: WsChatBridge = scriptedBridge(script) { busy }
        val coordinator = WsChatSendCoordinator(
            scope = scope.backgroundScope,
            agentId = AGENT,
            activeConfig = { CONFIG },
            wsChatBridge = bridge,
            timelineRepository = timeline,
            conversationRepository = conversationRepository(),
            uiState = uiState,
            clearComposerAfterSend = { composerCleared = true },
            activeConversationId = { activeConversation },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersionProvider = CLIENT_VERSION,
        )

        fun visibleQueueTexts(): List<String> = uiState.value.sendQueue.items.map { it.text }

        fun queueOf(conversationId: String) = coordinator.sendQueue.state.value[QueueConversationId(conversationId)]

        /** The running turn ends and the transport retires it. */
        suspend fun finishTurn(status: BridgeTurnStatus) {
            busy = false
            coordinator.handleEvent(WsTimelineEvent.TurnDone(turnId = "turn-1", runId = "run-1", status = status))
            scope.advanceUntilIdle()
            scope.runCurrent()
        }

        fun verifySent(text: String, times: Int, conversationId: String = "conv-1") {
            verify(exactly = times) {
                bridge.send(
                    agentId = AGENT,
                    conversationId = conversationId,
                    text = text,
                    otid = any(),
                    attachments = emptyList(),
                )
            }
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        val CONFIG = LettaConfig(
            id = "shim",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8291",
            accessToken = "token",
        )
        val CLIENT_VERSION = object : ChatClientVersionProvider {
            override val clientVersion: String = "letta-mobile/test (android)"
        }

        fun scriptedBridge(script: BridgeScript, busy: () -> Boolean): WsChatBridge = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(ChannelTransportState.Connected("server-1", "sess-1", "android-letta-mobile"))
            every { events } returns emptyFlow()
            every { redialWhileTurnActive } returns emptyFlow()
            every { isConnected() } returns true
            coEvery { awaitConnected() } returns WsConnectionState.Connected(a2uiEnabled = false, catalog = null)
            every { send(any(), any(), any(), any(), any(), any()) } returnsMany script.sendResults
            every { cancel(any()) } returns script.cancelResult
            every { hasActiveChatTurn(any()) } answers { busy() }
        }

        fun conversationRepository(): ConversationRepository = mockk(relaxed = true) {
            coEvery { createConversation(AgentId(AGENT), any()) } returns Conversation(
                id = com.letta.mobile.data.model.ConversationId("conv-default-agent-1"),
                agentId = AgentId(AGENT),
                createdAt = "1970-01-01T00:00:00Z",
                updatedAt = "1970-01-01T00:00:00Z",
                lastMessageAt = "1970-01-01T00:00:00Z",
            )
        }
    }
}
