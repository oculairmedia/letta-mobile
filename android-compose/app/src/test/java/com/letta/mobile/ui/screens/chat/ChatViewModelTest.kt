package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.testutil.TestData
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

    private lateinit var fakeMessageRepo: FakeMessageRepository
    private lateinit var fakeAgentRepo: FakeAgentRepository
    private lateinit var fakeConversationRepo: FakeConversationRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeMessageRepo = FakeMessageRepository()
        fakeAgentRepo = FakeAgentRepository()
        fakeConversationRepo = FakeConversationRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        agentId: String = "agent-1",
        conversationId: String? = "conv-1",
    ): ChatViewModel {
        val savedState = SavedStateHandle().apply {
            set("agentId", agentId)
            conversationId?.let { set("conversationId", it) }
        }
        return ChatViewModel(savedState, fakeMessageRepo, fakeAgentRepo, fakeConversationRepo)
    }

    @Test
    fun `loadMessages populates messages and agent name`() = runTest {
        fakeAgentRepo.setAgent(TestData.agent(id = "agent-1", name = "Test Agent"))
        fakeMessageRepo.setMessages(listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Hello"),
            TestData.appMessage(id = "2", messageType = MessageType.ASSISTANT, content = "Hi"),
        ))

        val vm = createViewModel()
        val state = vm.uiState.value

        assertEquals(2, state.messages.size)
        assertEquals("Test Agent", state.agentName)
        assertFalse(state.isLoadingMessages)
    }

    @Test
    fun `loadMessages sets error on failure`() = runTest {
        fakeAgentRepo.shouldFail = true

        val vm = createViewModel()
        val state = vm.uiState.value

        assertTrue(state.error != null)
    }

    @Test
    fun `sendMessage shows user message immediately`() = runTest {
        fakeAgentRepo.setAgent(TestData.agent(id = "agent-1", name = "Agent"))
        fakeMessageRepo.setMessages(emptyList())
        fakeMessageRepo.setStreamStates(listOf(StreamState.Sending))

        val vm = createViewModel()
        vm.sendMessage("Hello agent")
        val state = vm.uiState.value

        assertTrue(state.messages.any { it.content == "Hello agent" && it.role == "user" })
    }

    @Test
    fun `sendMessage appends response after user message and history`() = runTest {
        val existingMessages = listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "First question"),
            TestData.appMessage(id = "2", messageType = MessageType.ASSISTANT, content = "First answer"),
        )
        val streamResponse = listOf(
            TestData.appMessage(id = "3", messageType = MessageType.ASSISTANT, content = "Second answer"),
        )

        fakeAgentRepo.setAgent(TestData.agent(id = "agent-1", name = "Agent"))
        fakeMessageRepo.setMessages(existingMessages)
        fakeMessageRepo.setStreamStates(listOf(StreamState.Complete(streamResponse)))

        val vm = createViewModel()
        assertEquals(2, vm.uiState.value.messages.size)

        vm.sendMessage("Second question")
        val state = vm.uiState.value

        assertTrue(state.messages.size >= 4)
        assertEquals("First question", state.messages[0].content)
        assertEquals("First answer", state.messages[1].content)
        assertEquals("Second question", state.messages[2].content)
        assertEquals("Second answer", state.messages[3].content)
    }

    @Test
    fun `sendMessage does NOT replace history with streaming response`() = runTest {
        val history = listOf(
            TestData.appMessage(id = "old-1", messageType = MessageType.USER, content = "Old message 1"),
            TestData.appMessage(id = "old-2", messageType = MessageType.ASSISTANT, content = "Old reply 1"),
        )
        val streamChunk = listOf(
            TestData.appMessage(id = "new-1", messageType = MessageType.ASSISTANT, content = "New partial"),
        )

        fakeAgentRepo.setAgent(TestData.agent(id = "agent-1", name = "Agent"))
        fakeMessageRepo.setMessages(history)
        fakeMessageRepo.setStreamStates(listOf(StreamState.Streaming(streamChunk)))

        val vm = createViewModel()
        assertEquals(2, vm.uiState.value.messages.size)

        vm.sendMessage("New question")
        val state = vm.uiState.value

        assertTrue(state.messages.size >= 4)
        assertEquals("Old message 1", state.messages[0].content)
        assertEquals("Old reply 1", state.messages[1].content)
        assertEquals("New question", state.messages[2].content)
        assertEquals("New partial", state.messages[3].content)
    }

    @Test
    fun `sendMessage clears input and sets streaming`() = runTest {
        fakeAgentRepo.setAgent(TestData.agent(id = "agent-1", name = "Agent"))
        fakeMessageRepo.setMessages(emptyList())
        fakeMessageRepo.setStreamStates(listOf(StreamState.Sending))

        val vm = createViewModel()
        vm.updateInputText("Hello")
        vm.sendMessage("Hello")

        assertEquals("", vm.uiState.value.inputText)
        assertTrue(vm.uiState.value.isStreaming)
    }

    @Test
    fun `updateInputText only changes input field`() = runTest {
        fakeAgentRepo.setAgent(TestData.agent(id = "agent-1", name = "Agent"))
        fakeMessageRepo.setMessages(listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Msg"),
        ))

        val vm = createViewModel()
        vm.updateInputText("typing...")

        assertEquals("typing...", vm.uiState.value.inputText)
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun `blank agentId sets error`() = runTest {
        val vm = createViewModel(agentId = "")
        assertTrue(vm.uiState.value.error != null)
    }

    private class FakeMessageRepository : MessageRepository(null!!, null!!) {
        private var messages: List<AppMessage> = emptyList()
        private var streamStates: List<StreamState> = emptyList()

        fun setMessages(list: List<AppMessage>) { messages = list }
        fun setStreamStates(states: List<StreamState>) { streamStates = states }

        override fun getMessages(agentId: String, conversationId: String?): Flow<List<AppMessage>> = flowOf(messages)
        override fun sendMessage(agentId: String, text: String, conversationId: String?): Flow<StreamState> = flow {
            streamStates.forEach { emit(it) }
        }
        override suspend fun fetchMessages(agentId: String, conversationId: String?): List<AppMessage> = messages
    }

    private class FakeAgentRepository : AgentRepository(null!!, null!!) {
        private var agent: Agent? = null
        var shouldFail = false

        fun setAgent(a: Agent) { agent = a }

        override fun getAgent(id: String): Flow<Agent> {
            if (shouldFail || agent == null) return flow { throw Exception("Failed to load agent") }
            return flowOf(agent!!)
        }
    }

    private class FakeConversationRepository : ConversationRepository(null!!) {
        override fun getConversations(agentId: String): Flow<List<Conversation>> = flowOf(listOf(
            TestData.conversation(id = "conv-1", agentId = agentId)
        ))
        override suspend fun refreshConversations(agentId: String) {}
        override suspend fun createConversation(agentId: String, summary: String?): Conversation =
            TestData.conversation(id = "new-conv", agentId = agentId, summary = summary)
        override suspend fun updateConversation(id: String, agentId: String, summary: String) {}
    }
}
