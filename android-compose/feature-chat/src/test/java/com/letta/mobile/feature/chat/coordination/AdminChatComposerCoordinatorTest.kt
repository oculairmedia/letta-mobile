package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatComposerCoordinatorTest {

    private lateinit var coordinator: AdminChatComposerCoordinator
    private val testScope = TestScope()
    private val composerController = mockk<ChatComposerController>(relaxed = true)
    private val chatSendStrategySelector = mockk<ChatSendStrategySelector>(relaxed = true)
    private val chatBannerController = mockk<ChatBannerController>(relaxed = true)
    private val slashCommandRepository = mockk<ISlashCommandRepository>(relaxed = true)
    private val uiState = MutableStateFlow(ChatUiState())

    @Before
    fun setup() {
        val stateFlow = MutableStateFlow(ChatComposerState())
        every { composerController.state } returns stateFlow
        

        coordinator = AdminChatComposerCoordinator(
            scope = testScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            uiState = uiState,
            agentId = AgentId("agent_123"),
            explicitConversationId = "conv_123",
            backendKind = { BackendKind.DIRECT_WS },
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
    fun `submitComposer queues a message sent while streaming`() {
        coordinator = AdminChatComposerCoordinator(
            scope = testScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            uiState = uiState,
            agentId = AgentId("agent_123"),
            explicitConversationId = "conv_123",
            backendKind = { BackendKind.DIRECT_WS },
            slashCommandRepository = slashCommandRepository,
            isStreaming = { true },
            projectContextAvailable = true,
        )
        
        uiState.value = ChatUiState(conversationState = ConversationState.Ready("conv_123"), isStreaming = true)
        every { composerController.payloadForSend("hello") } returns
            ComposerSendPayload(text = "hello", attachments = emptyList())

        coordinator.submitComposer("hello")

        verify(exactly = 0) { composerController.setError(any()) }
        verify { chatSendStrategySelector.send(eq("hello"), any(), any()) }
    }

    @Test
    fun `free text answers a pending single question instead of starting a turn`() {
        val submitted = mutableListOf<Pair<AdminChatComposerCoordinator.PendingUserInput, String>>()
        val pending = AdminChatComposerCoordinator.PendingUserInput(
            requestId = "req-1",
            toolCallId = "tool-1",
            arguments = """{"questions":[{"question":"Which route?","options":[{"label":"Direct"}]}]}""",
            question = "Which route?",
        )
        coordinator = AdminChatComposerCoordinator(
            scope = testScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            uiState = uiState,
            agentId = AgentId("agent_123"),
            explicitConversationId = "conv_123",
            backendKind = { BackendKind.DIRECT_WS },
            slashCommandRepository = slashCommandRepository,
            isStreaming = { true },
            pendingUserInput = { pending },
            submitUserInput = { input, reason -> submitted += input to reason },
            projectContextAvailable = true,
        )

        coordinator.submitComposer("route over Iroh")

        assertEquals(1, submitted.size)
        assertEquals(pending, submitted.single().first)
        val updatedInput = AskUserQuestion.decodeAnswerReason(submitted.single().second)
        assertEquals("route over Iroh", updatedInput?.get("answers")?.jsonObject?.get("Which route?")?.jsonPrimitive?.content)
        verify { composerController.clearText() }
        verify(exactly = 0) { chatSendStrategySelector.send(any(), any(), any()) }
        verify(exactly = 0) { composerController.setError(any()) }
    }

    @Test
    fun `sendMessage clears a stale error banner from a prior failed turn`() {
        // letta-mobile-aujte: a red 'turn failed' banner from a PREVIOUS turn
        // must not survive into a new send — ChatBannerController.showError()
        // is the only thing that ever sets ChatUiState.error on the failure
        // path; sendMessagePayload() must be the counterpart that clears it.
        uiState.value = ChatUiState(
            conversationState = ConversationState.Ready("conv_123"),
            error = "This turn failed before the assistant could reply.",
        )
        every { composerController.payloadForSend("hello") } returns
            ComposerSendPayload(text = "hello", attachments = emptyList())

        coordinator.sendMessage("hello")

        verify { chatBannerController.clearError() }
        verify { chatSendStrategySelector.send(eq("hello"), any(), any()) }
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
        coordinator.interruptRun()

        verify(exactly = 0) { chatBannerController.beginCancelling() }
    }
    
    @Test
    fun `interruptRun cancels the turn through the channel route`() {
        uiState.value = ChatUiState(isStreaming = true)
        
        coordinator.interruptRun()
        testScope.runCurrent()

        // letta-mobile-lgns8.19: the UI enters CANCELLING — it is NOT optimistically
        // cleared. The idle transition happens on the terminal frame instead.
        verify { chatBannerController.beginCancelling() }
        verify(exactly = 0) { chatBannerController.forceClearStreamingAfterInterrupt() }
        verify { chatSendStrategySelector.cancel() }
    }
}
