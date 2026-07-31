package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * letta-mobile-lgns8.19 — Stop must not optimistically settle the UI.
 *
 * These tests drive a REAL [ChatBannerController] over a real [ChatUiState]
 * flow (the production coordinator's only route to streaming state), so they
 * fail on revert of the cancelling-state change rather than merely restating
 * the mock interactions.
 */
class AdminChatComposerCoordinatorStopAbortTest {

    private val testScope = TestScope()
    private val composerController = mockk<ChatComposerController>(relaxed = true)
    private val chatSendStrategySelector = mockk<ChatSendStrategySelector>(relaxed = true)
    private val messageRepository = mockk<IMessageRepository>(relaxed = true)
    private val slashCommandRepository = mockk<ISlashCommandRepository>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val uiState = MutableStateFlow(ChatUiState())
    private lateinit var bannerController: ChatBannerController
    private lateinit var coordinator: AdminChatComposerCoordinator

    private val noopClearThinking: () -> Unit = {}

    @Before
    fun setup() {
        every { composerController.state } returns MutableStateFlow(ChatComposerState())
        every { composerController.payloadForSend(any()) } answers {
            ComposerSendPayload(text = firstArg(), attachments = emptyList())
        }
        every { sessionManager.current } returns mockk(relaxed = true) {
            every { localRuntimeBackend } returns null
        }
        coEvery { messageRepository.cancelMessage(any<AgentId>(), any<List<String>>()) } returns emptyMap<String, String>()
        bannerController = ChatBannerController(uiState, composerController)
        coordinator = AdminChatComposerCoordinator(
            scope = testScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = bannerController,
            uiState = uiState,
            agentId = AgentId("agent_123"),
            explicitConversationId = "conv_123",
            isShimBackend = { false },
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            slashCommandRepository = slashCommandRepository,
            isStreaming = { uiState.value.isStreaming },
            projectContextAvailable = true,
        )
    }

    private fun beginStreamingTurn() {
        uiState.value = ChatUiState(
            conversationState = ConversationState.Ready("conv_123"),
            isStreaming = true,
            isAgentTyping = true,
        )
    }

    /** The authoritative terminal frame: the only thing allowed to settle the turn. */
    private fun deliverTerminalFrame() {
        uiState.value = uiState.value.copy(isStreaming = false, isAgentTyping = false)
    }

    @Test
    fun `stop holds streaming and cancelling visible until the terminal frame`() {
        beginStreamingTurn()

        coordinator.interruptRun(noopClearThinking)

        // No optimistic clear: the server turn is still running, so the UI must
        // still read as busy, now explicitly "cancelling".
        assertTrue("streaming must survive the cancel request", uiState.value.isStreaming)
        assertTrue(uiState.value.isCancelling)
        assertTrue(uiState.value.isCancellingRun)

        testScope.runCurrent()
        coVerify { messageRepository.cancelMessage(eq(AgentId("agent_123")), any()) }
        assertTrue("still cancelling after the abort is dispatched", uiState.value.isCancellingRun)

        deliverTerminalFrame()
        testScope.runCurrent()

        assertFalse(uiState.value.isStreaming)
        assertFalse(uiState.value.isCancelling)
        assertFalse(uiState.value.isCancellingRun)
    }

    @Test
    fun `send is rejected while a stop is pending so frames cannot interleave`() {
        beginStreamingTurn()
        coordinator.interruptRun(noopClearThinking)
        testScope.runCurrent()

        // A frame lands between Stop and the terminal — the turn is still alive.
        uiState.value = uiState.value.copy(isAgentTyping = true)

        coordinator.sendMessage("a new message")
        coordinator.submitComposer("a new message")
        testScope.runCurrent()

        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
        verify(atLeast = 1) {
            composerController.setError(
                AdminChatComposerCoordinator.STOPPING_SEND_BLOCKED_MESSAGE,
            )
        }
        assertTrue(uiState.value.isCancellingRun)
    }

    @Test
    fun `send is accepted again once the terminal frame settles the cancelled turn`() {
        beginStreamingTurn()
        coordinator.interruptRun(noopClearThinking)
        testScope.runCurrent()
        deliverTerminalFrame()
        testScope.runCurrent()

        coordinator.sendMessage("clean next turn")
        testScope.runCurrent()

        verify(exactly = 1) { chatSendStrategySelector.send(eq("clean next turn"), any(), any()) }
    }

    @Test
    fun `second stop press force clears locally as the escape hatch`() {
        beginStreamingTurn()
        coordinator.interruptRun(noopClearThinking)
        testScope.runCurrent()
        assertTrue(uiState.value.isStreaming)

        coordinator.interruptRun(noopClearThinking)

        assertFalse("second stop press force-clears the local UI", uiState.value.isStreaming)
        assertFalse(uiState.value.isCancelling)
        assertFalse(uiState.value.isAgentTyping)
    }

    @Test
    fun `late frames after the cancelled turn's terminal do not reopen streaming`() {
        beginStreamingTurn()
        coordinator.interruptRun(noopClearThinking)
        testScope.runCurrent()
        deliverTerminalFrame()
        testScope.runCurrent()

        // Ghost resume: the killed turn's tail arrives and tries to re-open the
        // streaming UI without any new user send.
        uiState.value = uiState.value.copy(isStreaming = true, isAgentTyping = true)
        testScope.runCurrent()

        assertFalse("late frame must not resurrect the cancelled turn", uiState.value.isStreaming)
        assertFalse(uiState.value.isAgentTyping)
    }

    @Test
    fun `a failed abort dispatch falls back to the local clear instead of wedging`() {
        beginStreamingTurn()
        coEvery { messageRepository.cancelMessage(any<AgentId>(), any<List<String>>()) } throws IllegalStateException("socket down")

        coordinator.interruptRun(noopClearThinking)
        assertTrue(uiState.value.isCancellingRun)
        testScope.runCurrent()

        assertFalse(uiState.value.isStreaming)
        assertFalse(uiState.value.isCancelling)
    }

    @Test
    fun `stop is a no-op when nothing is streaming`() {
        uiState.value = ChatUiState(isStreaming = false)

        coordinator.interruptRun(noopClearThinking)
        testScope.runCurrent()

        assertFalse(uiState.value.isCancelling)
        assertEquals(false, uiState.value.isCancellingRun)
        coVerify(exactly = 0) { messageRepository.cancelMessage(any<AgentId>(), any<List<String>>()) }
    }
}
