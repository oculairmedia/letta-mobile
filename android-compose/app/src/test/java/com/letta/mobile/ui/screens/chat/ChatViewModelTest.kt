package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private lateinit var fakeMessageRepository: FakeMessageRepository
    private lateinit var fakeAgentRepository: FakeAgentRepository
    private lateinit var fakeConversationRepository: FakeConversationRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: ChatViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeMessageRepository = FakeMessageRepository()
        fakeAgentRepository = FakeAgentRepository()
        fakeConversationRepository = FakeConversationRepository()
        savedStateHandle = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadMessages_setsLoading_thenSuccess() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        val testMessages = listOf(
            TestData.appMessage(id = "msg-1", messageType = MessageType.USER, content = "Hello"),
            TestData.appMessage(id = "msg-2", messageType = MessageType.ASSISTANT, content = "Hi there")
        )
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(testMessages)

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(2, successState.messages.size)
            assertEquals("Test Agent", successState.agentName)
        }
    }

    @Test
    fun loadMessages_setsAgentName_fromRepository() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Custom Agent Name")
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("Custom Agent Name", successState.agentName)
        }
    }

    @Test
    fun loadMessages_mapsAppMessages_toUiMessages_correctly() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        val testMessages = listOf(
            TestData.appMessage(id = "msg-1", messageType = MessageType.USER, content = "User message"),
            TestData.appMessage(id = "msg-2", messageType = MessageType.ASSISTANT, content = "Assistant message"),
            TestData.appMessage(id = "msg-3", messageType = MessageType.REASONING, content = "Reasoning content"),
            TestData.appMessage(id = "msg-4", messageType = MessageType.TOOL_CALL, content = "{\"arg\":\"value\"}", toolName = "test_tool"),
            TestData.appMessage(id = "msg-5", messageType = MessageType.TOOL_RETURN, content = "Tool result", toolCallId = "call-1")
        )
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(testMessages)

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(5, successState.messages.size)
            
            // User message
            assertEquals("user", successState.messages[0].role)
            assertEquals("User message", successState.messages[0].content)
            assertFalse(successState.messages[0].isReasoning)
            
            // Assistant message
            assertEquals("assistant", successState.messages[1].role)
            assertEquals("Assistant message", successState.messages[1].content)
            assertFalse(successState.messages[1].isReasoning)
            
            // Reasoning message
            assertEquals("assistant", successState.messages[2].role)
            assertTrue(successState.messages[2].isReasoning)
            
            // Tool call message
            assertEquals("tool", successState.messages[3].role)
            assertEquals(1, successState.messages[3].toolCalls?.size)
            assertEquals("test_tool", successState.messages[3].toolCalls?.get(0)?.name)
            
            // Tool return message
            assertEquals("tool", successState.messages[4].role)
            assertEquals("Tool result", successState.messages[4].content)
        }
    }

    @Test
    fun loadMessages_setsError_onRepositoryFailure() = runTest {
        fakeMessageRepository.shouldFail = true

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Error)
            val errorState = state as UiState.Error
            assertEquals("Failed to load agent", errorState.message)
        }
    }

    @Test
    fun sendMessage_clearsInputText_immediately() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())
        fakeMessageRepository.setStreamStates(listOf(StreamState.Complete(emptyList())))

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.updateInputText("Test message")
        viewModel.sendMessage("Test message")

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("", successState.inputText)
        }
    }

    @Test
    fun sendMessage_setsIsStreaming_andIsAgentTyping() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())
        fakeMessageRepository.setStreamStates(listOf(StreamState.Sending))

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.sendMessage("Test message")

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertTrue(successState.isStreaming)
            assertTrue(successState.isAgentTyping)
        }
    }

    @Test
    fun sendMessage_updatesMessages_onStreamingState() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        val streamingMessages = listOf(
            TestData.appMessage(id = "msg-1", messageType = MessageType.USER, content = "Test"),
            TestData.appMessage(id = "msg-2", messageType = MessageType.ASSISTANT, content = "Response")
        )
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())
        fakeMessageRepository.setStreamStates(listOf(
            StreamState.Sending,
            StreamState.Streaming(streamingMessages)
        ))

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.sendMessage("Test")

        viewModel.uiState.test {
            // Skip initial state
            awaitItem()
            
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(2, successState.messages.size)
            assertTrue(successState.isStreaming)
        }
    }

    @Test
    fun sendMessage_clearsTyping_onFirstStreaming() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        val streamingMessages = listOf(
            TestData.appMessage(id = "msg-1", messageType = MessageType.USER, content = "Test")
        )
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())
        fakeMessageRepository.setStreamStates(listOf(
            StreamState.Sending,
            StreamState.Streaming(streamingMessages)
        ))

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.sendMessage("Test")

        viewModel.uiState.test {
            // Skip initial state
            awaitItem()
            
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertFalse(successState.isAgentTyping)
        }
    }

    @Test
    fun sendMessage_setsStreamingFalse_onComplete() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        val completeMessages = listOf(
            TestData.appMessage(id = "msg-1", messageType = MessageType.USER, content = "Test"),
            TestData.appMessage(id = "msg-2", messageType = MessageType.ASSISTANT, content = "Response")
        )
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())
        fakeMessageRepository.setStreamStates(listOf(
            StreamState.Sending,
            StreamState.Complete(completeMessages)
        ))

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.sendMessage("Test")

        viewModel.uiState.test {
            // Skip initial state
            awaitItem()
            
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertFalse(successState.isStreaming)
            assertFalse(successState.isAgentTyping)
            assertEquals(2, successState.messages.size)
        }
    }

    @Test
    fun sendMessage_setsError_onStreamError() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(emptyList())
        fakeMessageRepository.setStreamStates(listOf(
            StreamState.Sending,
            StreamState.Error("Network error")
        ))

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.sendMessage("Test")

        viewModel.uiState.test {
            // Skip initial state
            awaitItem()
            
            val state = awaitItem()
            assertTrue(state is UiState.Error)
            val errorState = state as UiState.Error
            assertEquals("Network error", errorState.message)
        }
    }

    @Test
    fun updateInputText_updatesOnlyInputField() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        val testMessages = listOf(
            TestData.appMessage(id = "msg-1", messageType = MessageType.USER, content = "Hello")
        )
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(testMessages)

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.updateInputText("New input text")

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("New input text", successState.inputText)
            assertEquals(1, successState.messages.size)
            assertFalse(successState.isStreaming)
            assertFalse(successState.isAgentTyping)
        }
    }

    @Test
    fun toUiMessage_mapsAllMessageTypes() = runTest {
        val testAgent = TestData.agent(id = "agent-1", name = "Test Agent")
        
        // Test all message types
        val testMessages = listOf(
            TestData.appMessage(id = "user-1", messageType = MessageType.USER, content = "User"),
            TestData.appMessage(id = "assistant-1", messageType = MessageType.ASSISTANT, content = "Assistant"),
            TestData.appMessage(id = "reasoning-1", messageType = MessageType.REASONING, content = "Reasoning"),
            TestData.appMessage(id = "tool-call-1", messageType = MessageType.TOOL_CALL, content = "args", toolName = "my_tool"),
            TestData.appMessage(id = "tool-return-1", messageType = MessageType.TOOL_RETURN, content = "result")
        )
        
        fakeAgentRepository.setAgent(testAgent)
        fakeMessageRepository.setMessages(testMessages)

        viewModel = ChatViewModel(
            savedStateHandle,
            fakeMessageRepository,
            fakeAgentRepository,
            fakeConversationRepository
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            
            // Verify all mappings
            assertEquals("user", successState.messages[0].role)
            assertEquals("User", successState.messages[0].content)
            assertFalse(successState.messages[0].isReasoning)
            
            assertEquals("assistant", successState.messages[1].role)
            assertEquals("Assistant", successState.messages[1].content)
            assertFalse(successState.messages[1].isReasoning)
            
            assertEquals("assistant", successState.messages[2].role)
            assertEquals("Reasoning", successState.messages[2].content)
            assertTrue(successState.messages[2].isReasoning)
            
            assertEquals("tool", successState.messages[3].role)
            assertEquals("args", successState.messages[3].content)
            assertEquals("my_tool", successState.messages[3].toolCalls?.get(0)?.name)
            assertEquals("args", successState.messages[3].toolCalls?.get(0)?.arguments)
            
            assertEquals("tool", successState.messages[4].role)
            assertEquals("result", successState.messages[4].content)
        }
    }

    private class FakeMessageRepository : MessageRepository(null!!, null!!) {
        private var messages: List<AppMessage> = emptyList()
        private var streamStates: List<StreamState> = emptyList()
        var shouldFail = false

        fun setMessages(messageList: List<AppMessage>) {
            messages = messageList
        }

        fun setStreamStates(states: List<StreamState>) {
            streamStates = states
        }

        override fun getMessages(agentId: String, conversationId: String?): Flow<List<AppMessage>> {
            if (shouldFail) {
                throw Exception("Repository error")
            }
            return flowOf(messages)
        }

        override fun sendMessage(
            agentId: String,
            text: String,
            conversationId: String?
        ): Flow<StreamState> = flow {
            if (shouldFail) {
                throw Exception("Send failed")
            }
            streamStates.forEach { state ->
                emit(state)
            }
        }

        override suspend fun fetchMessages(agentId: String, conversationId: String?): List<AppMessage> {
            if (shouldFail) {
                throw Exception("Fetch failed")
            }
            return messages
        }
    }

    private class FakeAgentRepository : AgentRepository(null!!) {
        private var agent: Agent? = null

        fun setAgent(testAgent: Agent) {
            agent = testAgent
        }

        override fun getAgent(id: String): Flow<Agent> {
            return if (agent != null) {
                flowOf(agent!!)
            } else {
                flow { throw Exception("Failed to load agent") }
            }
        }
    }

    private class FakeConversationRepository : ConversationRepository(null!!)
}
