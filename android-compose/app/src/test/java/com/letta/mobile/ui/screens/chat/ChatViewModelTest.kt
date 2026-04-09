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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var conversationRepository: ConversationRepository
    private val testDispatcher = UnconfinedTestDispatcher()
    private var messages: List<AppMessage> = emptyList()
    private var streamStates: List<StreamState> = emptyList()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        messageRepository = mockk(relaxed = true)
        agentRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)

        every { messageRepository.getMessages(any(), any()) } answers { flowOf(messages) }
        coEvery { messageRepository.fetchMessages(any(), any()) } answers { messages }
        every { messageRepository.sendMessage(any(), any(), any()) } answers {
            flow {
                streamStates.forEach { emit(it) }
            }
        }
        every { agentRepository.getAgent(any()) } returns flowOf(TestData.agent(id = "agent-1", name = "Test Agent"))
        every { conversationRepository.getConversations(any()) } answers {
            flowOf(listOf(TestData.conversation(id = "conv-1", agentId = firstArg())))
        }
        coEvery { conversationRepository.refreshConversations(any()) } returns Unit
        coEvery { conversationRepository.createConversation(any(), any()) } answers {
            TestData.conversation(id = "new-conv", agentId = firstArg(), summary = secondArg())
        }
        coEvery { conversationRepository.updateConversation(any(), any(), any()) } returns Unit
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
        return ChatViewModel(savedState, messageRepository, agentRepository, conversationRepository)
    }

    @Test
    fun `loadMessages populates messages and agent name`() = runTest {
        messages = listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Hello"),
            TestData.appMessage(id = "2", messageType = MessageType.ASSISTANT, content = "Hi"),
        )

        val vm = createViewModel()
        val state = vm.uiState.value

        assertEquals(2, state.messages.size)
        assertEquals("Test Agent", state.agentName)
        assertFalse(state.isLoadingMessages)
    }

    @Test
    fun `loadMessages preserves repository order instead of resorting by date`() = runTest {
        messages = listOf(
            AppMessage(
                id = "assistant-first",
                date = Instant.parse("2024-03-15T11:00:00Z"),
                messageType = MessageType.ASSISTANT,
                content = "Assistant came first in repository order",
            ),
            AppMessage(
                id = "user-second",
                date = Instant.parse("2024-03-15T10:00:00Z"),
                messageType = MessageType.USER,
                content = "User came second in repository order",
            ),
        )

        val vm = createViewModel()

        assertEquals(
            listOf("assistant-first", "user-second"),
            vm.uiState.value.messages.map { it.id },
        )
    }

    @Test
    fun `loadMessages sets error on failure`() = runTest {
        every { agentRepository.getAgent(any()) } returns flow { throw Exception("Failed to load agent") }

        val vm = createViewModel()
        val state = vm.uiState.value

        assertTrue(state.error != null)
    }

    @Test
    fun `sendMessage shows user message immediately`() = runTest {
        messages = emptyList()
        streamStates = listOf(StreamState.Sending)

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

        messages = existingMessages
        streamStates = listOf(StreamState.Complete(streamResponse))

        val vm = createViewModel()
        assertEquals(2, vm.uiState.value.messages.size)

        // Update fetchMessages to return what server would have after the send
        messages = existingMessages + listOf(
            TestData.appMessage(id = "user-q2", messageType = MessageType.USER, content = "Second question"),
        ) + streamResponse

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

        messages = history
        streamStates = listOf(StreamState.Streaming(streamChunk))

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
        messages = emptyList()
        streamStates = listOf(StreamState.Sending)

        val vm = createViewModel()
        vm.updateInputText("Hello")
        vm.sendMessage("Hello")

        assertEquals("", vm.inputText.value)
        assertTrue(vm.uiState.value.isStreaming)
    }

    @Test
    fun `updateInputText only changes input field`() = runTest {
        messages = listOf(TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Msg"))

        val vm = createViewModel()
        vm.updateInputText("typing...")

        assertEquals("typing...", vm.inputText.value)
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun `blank agentId sets error`() = runTest {
        val vm = createViewModel(agentId = "")
        assertTrue(vm.uiState.value.error != null)
    }
}
