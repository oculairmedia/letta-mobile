package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.feature.chat.send.ChatSendContext
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminChatComposerCoordinatorTest {

    private lateinit var coordinator: AdminChatComposerCoordinator
    private val testScope = TestScope()
    private val composerController = mockk<ChatComposerController>(relaxed = true)
    private val chatSendStrategySelector = mockk<ChatSendStrategySelector>(relaxed = true)
    private val chatBannerController = mockk<ChatBannerController>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val slashCommandRepository = mockk<ISlashCommandRepository>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val uiState = MutableStateFlow(ChatUiState())

    @Before
    fun setup() {
        val stateFlow = MutableStateFlow(ChatComposerState())
        every { composerController.state } returns stateFlow
        
        every { sessionManager.current } returns mockk(relaxed = true) {
            every { localRuntimeBackend } returns null
        }

        coordinator = AdminChatComposerCoordinator(
            scope = testScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            uiState = uiState,
            agentId = AgentId("agent_123"),
            explicitConversationId = "conv_123",
            isShimBackend = { false },
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            slashCommandRepository = slashCommandRepository,
            isStreaming = { false },
            projectContextAvailable = true,
        )
    }

    @Test
    fun `sendMessage does not send when loading`() {
        uiState.value = ChatUiState(conversationState = ConversationState.Loading)
        
        coordinator.sendMessage("hello")

        verify { chatBannerController.showConversationStillLoading() }
        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
    }

    @Test
    fun `sendMessage does not send when error`() {
        uiState.value = ChatUiState(conversationState = ConversationState.Error("test error"))
        
        coordinator.sendMessage("hello")

        verify { chatBannerController.showRetryConversationLoadBeforeSend() }
        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
    }
    
    @Test
    fun `submitComposer handles bug command`() {
        val stateFlow = MutableStateFlow(ChatComposerState(inputText = "/bug"))
        every { composerController.state } returns stateFlow
        
        val effect = coordinator.submitComposer("/bug")
        
        assertEquals(ChatComposerEffect.OpenBugReport, effect)
        verify { composerController.clearText() }
        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
    }
    
    @Test
    fun `submitComposer shows error if streaming`() {
        coordinator = AdminChatComposerCoordinator(
            scope = testScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            uiState = uiState,
            agentId = AgentId("agent_123"),
            explicitConversationId = "conv_123",
            isShimBackend = { false },
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            slashCommandRepository = slashCommandRepository,
            isStreaming = { true },
            projectContextAvailable = true,
        )
        
        coordinator.submitComposer("hello")
        
        verify { composerController.setError(any()) }
        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
    }

    @Test
    fun `rerunMessage ignores non-user messages`() {
        val message = mockk<UiMessage> {
            every { role } returns "assistant"
            every { content } returns "hello"
        }
        
        coordinator.rerunMessage(message)
        
        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
    }
    
    @Test
    fun `interruptRun does nothing if not streaming`() = runTest {
        uiState.value = ChatUiState(isStreaming = false)
        val clearThinking = mockk<() -> Unit>(relaxed = true)
        
        coordinator.interruptRun(clearThinking)
        
        verify(exactly = 0) { clearThinking.invoke() }
    }
    
    @Test
    fun `interruptRun cancels message if streaming and not shim`() {
        uiState.value = ChatUiState(isStreaming = true)
        val clearThinking = mockk<() -> Unit>(relaxed = true)
        coEvery { messageRepository.cancelMessage(eq(AgentId("agent_123")), any()) } returns emptyMap()
        
        coordinator.interruptRun(clearThinking)
        testScope.runCurrent()
        
        verify { clearThinking.invoke() }
        verify { chatBannerController.clearStreamingAfterInterrupt() }
        coVerify { messageRepository.cancelMessage(eq(AgentId("agent_123")), any()) }
    }
}
