package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeArchiveApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.FakeGroupApi
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakePassageApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProjectWorkApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.FakeToolApi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedModelSchedulePassageRepositoryTest {

    @Test
    fun `model schedule and passage repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeModelApi = FakeModelApi().apply {
            llmModels = mutableListOf(sampleLlmModel("llm-a"))
            embeddingModels = mutableListOf(sampleEmbeddingModel("embedding-a"))
        }
        val fakePassageApi = FakePassageApi().apply {
            setPassages("agent-1", listOf(Passage(id = "passage-a", text = "Backend A", agentId = "agent-1")))
        }
        val fakeScheduleApi = FakeScheduleApi().apply {
            schedules["agent-1"] = mutableListOf(sampleScheduledMessage("schedule-a"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                FakeAgentDao(),
                FakeConversationApi(),
                FakeConversationDao(),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                fakeModelApi,
                fakePassageApi,
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                fakeScheduleApi,
                FakeStepApi(),
                FakeToolApi(),
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val modelProxy = SessionScopedModelRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val passageProxy = SessionScopedPassageRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val scheduleProxy = SessionScopedScheduleRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val passages = passageProxy.getPassages("agent-1")

        modelProxy.refreshLlmModels()
        modelProxy.refreshEmbeddingModels()
        passageProxy.refreshPassages("agent-1")
        scheduleProxy.refreshSchedules("agent-1")
        advanceUntilIdle()
        assertEquals(listOf("llm-a"), modelProxy.llmModels.value.map { it.id })
        assertEquals(listOf("embedding-a"), modelProxy.embeddingModels.value.map { it.id })
        assertEquals(listOf("passage-a"), passages.value.map { it.id })
        assertEquals(listOf("schedule-a"), scheduleProxy.getSchedules("agent-1").first().map { it.id })

        fakeModelApi.llmModels = mutableListOf(sampleLlmModel("llm-b"))
        fakeModelApi.embeddingModels = mutableListOf(sampleEmbeddingModel("embedding-b"))
        fakePassageApi.setPassages(
            "agent-1",
            listOf(Passage(id = "passage-b", text = "Backend B", agentId = "agent-1")),
        )
        fakeScheduleApi.schedules["agent-1"] = mutableListOf(sampleScheduledMessage("schedule-b"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), modelProxy.llmModels.value.map { it.id })
        assertEquals(emptyList<String>(), modelProxy.embeddingModels.value.map { it.id })
        assertEquals(emptyList<String>(), passages.value.map { it.id })
        assertEquals(emptyList<String>(), scheduleProxy.getSchedules("agent-1").first().map { it.id })

        val rebuiltPassages = passageProxy.getPassages("agent-1")
        modelProxy.refreshLlmModels()
        modelProxy.refreshEmbeddingModels()
        passageProxy.refreshPassages("agent-1")
        scheduleProxy.refreshSchedules("agent-1")
        advanceUntilIdle()

        assertEquals(listOf("llm-b"), modelProxy.llmModels.value.map { it.id })
        assertEquals(listOf("embedding-b"), modelProxy.embeddingModels.value.map { it.id })
        assertEquals(listOf("passage-b"), rebuiltPassages.value.map { it.id })
        assertEquals(listOf("schedule-b"), scheduleProxy.getSchedules("agent-1").first().map { it.id })
    }

    private fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

    private fun config(id: String, serverUrl: String = "https://$id.example.test"): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = serverUrl,
    )

    private fun sampleLlmModel(id: String) = LlmModel(
        id = id,
        name = id,
        providerType = "openai",
    )

    private fun sampleEmbeddingModel(id: String) = EmbeddingModel(
        id = id,
        name = id,
        providerType = "openai",
    )

    private fun sampleScheduledMessage(id: String) = ScheduledMessage(
        id = id,
        agentId = "agent-1",
        message = SchedulePayload(
            messages = listOf(ScheduleMessage(content = "hello", role = "user")),
        ),
        schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
    )

    private class FakeAgentDao : AgentDao {
        private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

        override fun getAll(): Flow<List<AgentEntity>> = agents

        override suspend fun getAllOnce(): List<AgentEntity> = agents.value

        override suspend fun insertAll(agents: List<AgentEntity>) {
            this.agents.value = agents
        }

        override suspend fun upsert(agent: AgentEntity) {
            agents.value = agents.value.filterNot { it.id == agent.id } + agent
        }

        override suspend fun deleteExcept(keepIds: List<String>) {
            agents.value = agents.value.filter { it.id in keepIds }
        }

        override suspend fun deleteById(id: String) {
            agents.value = agents.value.filterNot { it.id == id }
        }

        override suspend fun deleteAll() {
            agents.value = emptyList()
        }
    }

    private class FakeConversationDao : ConversationDao {
        private val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
        private val refreshStates = mutableMapOf<String, ConversationRefreshEntity>()

        override fun observeForAgent(agentId: String): Flow<List<ConversationEntity>> =
            conversations.map { rows -> rows.filter { it.agentId == agentId } }

        override suspend fun getForAgentOnce(agentId: String): List<ConversationEntity> =
            conversations.value.filter { it.agentId == agentId }

        override suspend fun getAllOnce(): List<ConversationEntity> = conversations.value

        override suspend fun getByIdOnce(conversationId: String): ConversationEntity? =
            conversations.value.firstOrNull { it.id == conversationId }

        override suspend fun upsert(conversation: ConversationEntity) {
            conversations.value = conversations.value.filterNot { it.id == conversation.id } + conversation
        }

        override suspend fun upsertAll(conversations: List<ConversationEntity>) {
            conversations.forEach { upsert(it) }
        }

        override suspend fun delete(conversationId: String) {
            conversations.value = conversations.value.filterNot { it.id == conversationId }
        }

        override suspend fun deleteForAgent(agentId: String) {
            conversations.value = conversations.value.filterNot { it.agentId == agentId }
        }

        override suspend fun deleteForAgentExcept(agentId: String, keepIds: List<String>) {
            conversations.value = conversations.value.filterNot { it.agentId == agentId && it.id !in keepIds }
        }

        override suspend fun getRefreshState(agentId: String): ConversationRefreshEntity? = refreshStates[agentId]

        override suspend fun getAllRefreshStatesOnce(): List<ConversationRefreshEntity> = refreshStates.values.toList()

        override suspend fun upsertRefreshState(state: ConversationRefreshEntity) {
            refreshStates[state.agentId] = state
        }

        override suspend fun deleteAll() {
            conversations.value = emptyList()
        }

        override suspend fun deleteAllRefreshStates() {
            refreshStates.clear()
        }
    }
}
