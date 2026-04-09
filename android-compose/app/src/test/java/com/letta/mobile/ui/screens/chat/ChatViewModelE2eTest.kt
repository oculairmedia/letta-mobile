package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.domain.ClientToolRegistry
import com.letta.mobile.domain.MessageProcessor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.secondArg
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelE2eTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage processes SSE stream end to end for existing conversation`() = runTest {
        val messageRepository = createRealMessageRepository(
            streamConversationId = "conv-1",
            initialMessagesJson = "[]",
            reloadedMessagesJson = """
                [
                  {"id":"user-1","message_type":"user_message","content":"Hello agent"},
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi from stream"}
                ]
            """.trimIndent(),
            ssePayload = """
                data: {"id":"assistant-1","message_type":"assistant_message","content":"Hi from stream"}

                data: [DONE]

            """.trimIndent(),
        )
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        val agentRepo = mockk<AgentRepository>(relaxed = true)
        val conversations = listOf(Conversation(id = "conv-1", agentId = "agent-1", summary = "Existing"))
        every { conversationRepo.getConversations("agent-1") } returns flowOf(conversations)
        coEvery { conversationRepo.refreshConversations("agent-1") } returns Unit
        every { agentRepo.getAgent("agent-1") } returns flowOf(Agent(id = "agent-1", name = "Agent One"))

        val vm = ChatViewModel(
            SavedStateHandle(mapOf("agentId" to "agent-1", "conversationId" to "conv-1")),
            messageRepository,
            agentRepo,
            conversationRepo,
        )

        vm.sendMessage("Hello agent")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals("Hello agent", vm.uiState.value.messages[0].content)
        assertEquals("Hi from stream", vm.uiState.value.messages[1].content)
        assertFalse(vm.uiState.value.isStreaming)
        assertFalse(vm.uiState.value.isAgentTyping)
        assertEquals("", vm.inputText.value)
    }

    @Test
    fun `sendMessage auto creates conversation when none selected`() = runTest {
        val messageRepository = createRealMessageRepository(
            streamConversationId = "new-conv",
            initialMessagesJson = "[]",
            reloadedMessagesJson = """
                [
                  {"id":"user-2","message_type":"user_message","content":"Start a new thread"},
                  {"id":"assistant-2","message_type":"assistant_message","content":"New conversation reply"}
                ]
            """.trimIndent(),
            ssePayload = """
                data: {"id":"assistant-2","message_type":"assistant_message","content":"New conversation reply"}

                data: [DONE]

            """.trimIndent(),
        )
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        val agentRepo = mockk<AgentRepository>(relaxed = true)
        val conversations = mutableListOf<Conversation>()
        val createdSummaries = mutableListOf<String>()
        every { conversationRepo.getConversations("agent-1") } answers { flowOf(conversations.toList()) }
        coEvery { conversationRepo.refreshConversations("agent-1") } returns Unit
        coEvery { conversationRepo.createConversation("agent-1", any()) } answers {
            val summary = secondArg<String?>()
            createdSummaries.add(summary.orEmpty())
            val conversation = Conversation(id = "new-conv", agentId = "agent-1", summary = summary)
            conversations.add(conversation)
            conversation
        }
        every { agentRepo.getAgent("agent-1") } returns flowOf(Agent(id = "agent-1", name = "Agent One"))

        val vm = ChatViewModel(
            SavedStateHandle(mapOf("agentId" to "agent-1")),
            messageRepository,
            agentRepo,
            conversationRepo,
        )

        vm.sendMessage("Start a new thread")
        advanceUntilIdle()

        assertEquals(listOf("Start a new thread"), createdSummaries)
        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals("New conversation reply", vm.uiState.value.messages[1].content)
        assertTrue(vm.conversationId == "new-conv")
    }

    private fun createRealMessageRepository(
        streamConversationId: String,
        initialMessagesJson: String,
        reloadedMessagesJson: String,
        ssePayload: String,
    ): MessageRepository {
        var getCount = 0
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val client = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                req.method == HttpMethod.Post && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    respond(ByteReadChannel(ssePayload.toByteArray()), HttpStatusCode.OK, sseHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    getCount += 1
                    val body = if (getCount == 1) initialMessagesJson else reloadedMessagesJson
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
        }

        return MessageRepository(
            MessageApi(apiClient),
            MessageProcessor(ClientToolRegistry()),
        )
    }
}
