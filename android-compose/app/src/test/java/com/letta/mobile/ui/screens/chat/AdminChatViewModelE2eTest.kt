package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationManager
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.domain.ClientToolRegistry
import com.letta.mobile.domain.MessageProcessor
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.FakeFolderApi
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatViewModelE2eTest {
    private val trackedClients = mutableListOf<HttpClient>()

    private fun trackClient(client: HttpClient): HttpClient {
        trackedClients += client
        return client
    }

    @After
    fun closeTrackedClients() {
        trackedClients.forEach { it.close() }
        trackedClients.clear()
    }

    @Test
    fun `sendMessage processes SSE stream end to end for existing conversation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
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
            val conversationManager = ConversationManager(conversationRepo)
            val agentRepo = mockk<AgentRepository>(relaxed = true)
            val blockRepository = BlockRepository(FakeBlockApi())
            val bugReportRepository = mockk<BugReportRepository>(relaxed = true)
            val folderRepository = FolderRepository(FakeFolderApi())
            val conversations = listOf(Conversation(id = "conv-1", agentId = "agent-1", summary = "Existing"))
            every { conversationRepo.getConversations("agent-1") } returns flowOf(conversations)
            coEvery { conversationRepo.refreshConversations("agent-1") } returns Unit
            every { agentRepo.getAgent("agent-1") } returns flowOf(Agent(id = "agent-1", name = "Agent One"))

            val settingsRepo = mockk<SettingsRepository>(relaxed = true)
            every { settingsRepo.getChatBackgroundKey() } returns flowOf("default")
            every { settingsRepo.getChatFontScale() } returns flowOf(1f)
            every { settingsRepo.getUseTimelineSync() } returns flowOf(false)
            val botGateway = mockk<BotGateway>(relaxed = true)
            val botConfigStore = mockk<BotConfigStore>(relaxed = true)
            val internalBotClient = mockk<InternalBotClient>(relaxed = true)
            val timelineRepository = mockk<com.letta.mobile.data.timeline.TimelineRepository>(relaxed = true)

            val vm = AdminChatViewModel(
                SavedStateHandle(mapOf("agentId" to "agent-1", "conversationId" to "conv-1")),
                messageRepository,
                timelineRepository,
                agentRepo,
                blockRepository,
                bugReportRepository,
                folderRepository,
                conversationManager,
                conversationRepo,
                settingsRepo,
                botGateway,
                botConfigStore,
                internalBotClient,
            )
            advanceUntilIdle()

            vm.sendMessage("Hello agent")
            advanceUntilIdle()

            val state = vm.uiState.first {
                it.messages.size == 2 &&
                    !it.isStreaming &&
                    !it.isAgentTyping &&
                    it.messages.any { message -> message.content == "Hello agent" } &&
                    it.messages.any { message -> message.content == "Hi from stream" }
            }
            assertEquals(2, state.messages.size)
            assertTrue(state.messages.any { it.content == "Hello agent" && it.role == "user" })
            assertTrue(state.messages.any { it.content == "Hi from stream" && it.role == "assistant" })
            assertFalse(state.isStreaming)
            assertFalse(state.isAgentTyping)
            assertEquals("", vm.inputText.value)
            advanceUntilIdle()
        } finally {
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `sendMessage auto creates conversation when none selected`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
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
            val conversationManager = ConversationManager(conversationRepo)
            val agentRepo = mockk<AgentRepository>(relaxed = true)
            val blockRepository = BlockRepository(FakeBlockApi())
            val bugReportRepository = mockk<BugReportRepository>(relaxed = true)
            val folderRepository = FolderRepository(FakeFolderApi())
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

            val settingsRepo = mockk<SettingsRepository>(relaxed = true)
            every { settingsRepo.getChatBackgroundKey() } returns flowOf("default")
            every { settingsRepo.getChatFontScale() } returns flowOf(1f)
            every { settingsRepo.getUseTimelineSync() } returns flowOf(false)
            val botGateway = mockk<BotGateway>(relaxed = true)
            val botConfigStore = mockk<BotConfigStore>(relaxed = true)
            val internalBotClient = mockk<InternalBotClient>(relaxed = true)
            val timelineRepository = mockk<com.letta.mobile.data.timeline.TimelineRepository>(relaxed = true)

            val vm = AdminChatViewModel(
                SavedStateHandle(mapOf("agentId" to "agent-1")),
                messageRepository,
                timelineRepository,
                agentRepo,
                blockRepository,
                bugReportRepository,
                folderRepository,
                conversationManager,
                conversationRepo,
                settingsRepo,
                botGateway,
                botConfigStore,
                internalBotClient,
            )
            advanceUntilIdle()

            vm.sendMessage("Start a new thread")
            advanceUntilIdle()

            assertEquals(listOf("Start a new thread"), createdSummaries)
            val state = vm.uiState.first {
                it.messages.size == 2 &&
                    !it.isStreaming &&
                    !it.isAgentTyping &&
                    it.messages.any { message -> message.content == "Start a new thread" } &&
                    it.messages.any { message -> message.content == "New conversation reply" }
            }
            assertEquals(2, state.messages.size)
            assertEquals("New conversation reply", state.messages[1].content)
            assertTrue(vm.conversationId == "new-conv")
            advanceUntilIdle()
        } finally {
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
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
        val client = trackClient(HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                req.method == HttpMethod.Post && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    respond(ByteReadChannel(ssePayload.toByteArray()), HttpStatusCode.OK, sseHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/agents/") && url.contains("/messages") && req.url.parameters["conversation_id"] == streamConversationId -> {
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
        })

        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }

        return MessageRepository(
            MessageApi(apiClient),
            MessageProcessor(ClientToolRegistry()),
        )
    }
}
