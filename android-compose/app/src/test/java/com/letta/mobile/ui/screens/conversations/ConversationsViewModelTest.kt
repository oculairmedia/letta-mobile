package com.letta.mobile.ui.screens.conversations

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.testutil.FakeMessageApi
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlin.system.measureNanoTime
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ConversationsViewModelTest {

    private lateinit var fakeAllRepo: FakeAllConversationsRepository
    private lateinit var fakeConvRepo: FakeConversationRepository
    private lateinit var fakeAgentRepo: FakeAgentRepository
    private lateinit var fakeMessageRepo: FakeMessageRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var viewModel: ConversationsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAllRepo = FakeAllConversationsRepository()
        fakeAgentRepo = FakeAgentRepository()
        fakeConvRepo = FakeConversationRepository(fakeAgentRepo)
        fakeMessageRepo = FakeMessageRepository()
        settingsRepository = FakeSettingsRepository()
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadConversations populates state`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1", agentId = "a1")))

        viewModel.loadConversations()

        assertEquals(1, viewModel.uiState.value.conversations.size)
    }

    @Test
    fun `loadConversations skips refresh when caches are fresh`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1", agentId = "a1")))
        fakeAllRepo.fresh = true
        fakeAgentRepo.fresh = true
        fakeAllRepo.didRefresh = false
        fakeAgentRepo.didRefresh = false

        viewModel.loadConversations()

        assertFalse(fakeAllRepo.didRefresh)
        assertFalse(fakeAgentRepo.didRefresh)
    }

    @Test
    fun `loadConversations surfaces conversation refresh failure and stops loading`() = runTest {
        fakeAllRepo.refreshError = IllegalStateException("Conversations failed")

        viewModel.loadConversations()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Conversations failed", viewModel.uiState.value.error)
        assertEquals(0, viewModel.uiState.value.conversations.size)
    }

    @Test
    fun `loadConversations keeps conversations when agent refresh fails`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1", agentId = "a1")))
        fakeAgentRepo.refreshFailure = IllegalStateException("Agents failed")

        viewModel.loadConversations()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.error)
        assertEquals(1, viewModel.uiState.value.conversations.size)
    }

    @Test
    fun `loadConversations handles one thousand cached conversations quickly`() = runTest {
        fakeAllRepo.setConversations(
            List(1_000) { index ->
                TestData.conversation(id = "conversation-$index", agentId = "a1", summary = "Conversation $index")
            },
        )
        fakeAllRepo.fresh = true
        fakeAgentRepo.fresh = true

        val elapsedNanos = measureNanoTime {
            viewModel.loadConversations()
        }

        assertEquals(1_000, viewModel.uiState.value.conversations.size)
        assertTrue("1k conversation load should stay well under 2s", elapsedNanos < 2_000_000_000L)
    }


    @Test
    fun `empty install shows first run onboarding`() = runTest {
        fakeAgentRepo.setAgents(emptyList())
        fakeAllRepo.setConversations(emptyList())

        viewModel.loadConversations()

        assertTrue(viewModel.uiState.value.shouldShowFirstRunOnboarding())
    }

    @Test
    fun `existing agent suppresses first run onboarding`() = runTest {
        fakeAgentRepo.setAgents(listOf(Agent(id = AgentId("a1"), name = "Agent One")))
        fakeAllRepo.setConversations(emptyList())

        viewModel.loadConversations()

        assertFalse(viewModel.uiState.value.shouldShowFirstRunOnboarding())
    }

    @Test
    fun `existing conversation suppresses first run onboarding`() = runTest {
        fakeAgentRepo.setAgents(emptyList())
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1", agentId = "a1")))

        viewModel.loadConversations()

        assertFalse(viewModel.uiState.value.shouldShowFirstRunOnboarding())
    }

    @Test
    fun `local config filters cached remote state and shows first run onboarding`() = runTest {
        settingsRepository.saveConfig(localConfig())
        fakeAgentRepo.setAgents(listOf(Agent(id = AgentId("remote-agent"), name = "Remote Agent")))
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "remote-conv", agentId = "remote-agent")))
        fakeAllRepo.didRefresh = false
        fakeAgentRepo.didRefresh = false
        viewModel = newViewModel()

        viewModel.loadConversations()

        // letta-mobile-y5c9u/ajtu2: refreshes now flow unconditionally — the
        // local-vs-remote routing happens inside the repositories, which list
        // from the on-device store in local mode. The display filters still
        // keep remote-shaped cache rows out of local-mode UI state.
        assertTrue(fakeAgentRepo.didRefresh)
        assertTrue(fakeAllRepo.didRefresh)
        assertEquals(0, viewModel.uiState.value.agents.size)
        assertEquals(0, viewModel.uiState.value.conversations.size)
        assertTrue(viewModel.uiState.value.shouldShowFirstRunOnboarding())
    }

    @Test
    fun `local config displays local agents and conversations only`() = runTest {
        settingsRepository.saveConfig(localConfig())
        val localAgent = localAgent(id = "local-agent-1", name = "Local Agent")
        fakeAgentRepo.setAgents(
            listOf(
                Agent(id = AgentId("remote-agent"), name = "Remote Agent"),
                localAgent,
            )
        )
        fakeAllRepo.setConversations(
            listOf(
                TestData.conversation(id = "remote-conv", agentId = "remote-agent"),
                TestData.conversation(id = "local-agent-conv", agentId = localAgent.id.value),
                TestData.conversation(id = "local-conv-orphan", agentId = "missing-agent"),
            )
        )
        viewModel = newViewModel()

        viewModel.loadConversations()

        assertEquals(listOf(localAgent.id), viewModel.uiState.value.agents.map { it.id })
        assertEquals(
            setOf(ConversationId("local-agent-conv"), ConversationId("local-conv-orphan")),
            viewModel.uiState.value.conversations.map { it.conversation.id }.toSet(),
        )
        assertFalse(viewModel.uiState.value.shouldShowFirstRunOnboarding())
    }

    @Test
    fun `remote config keeps cached remote state and refresh behavior unchanged`() = runTest {
        settingsRepository.saveConfig(remoteConfig())
        fakeAgentRepo.setAgents(listOf(Agent(id = AgentId("remote-agent"), name = "Remote Agent")))
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "remote-conv", agentId = "remote-agent")))
        fakeAllRepo.didRefresh = false
        fakeAgentRepo.didRefresh = false
        viewModel = newViewModel()

        viewModel.loadConversations()

        assertTrue(fakeAgentRepo.didRefresh)
        assertTrue(fakeAllRepo.didRefresh)
        assertEquals(listOf(AgentId("remote-agent")), viewModel.uiState.value.agents.map { it.id })
        assertEquals(listOf(ConversationId("remote-conv")), viewModel.uiState.value.conversations.map { it.conversation.id })
        assertFalse(viewModel.uiState.value.shouldShowFirstRunOnboarding())
    }

    @Test
    fun `local config without model exposes setup readiness`() = runTest {
        settingsRepository.saveConfig(
            LettaConfig(
                id = "local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://runtime",
            )
        )

        assertTrue(viewModel.uiState.value.localLettaCodeReadiness.activeConfigIsLocal)
        assertFalse(viewModel.uiState.value.localLettaCodeReadiness.ready)
        assertEquals("Download or import a model in Settings before creating a local agent.", viewModel.uiState.value.localLettaCodeReadiness.setupMessage)
    }

    @Test
    fun `deleteConversation delegates to repository and removes from state`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1", agentId = "a1")))
        viewModel.loadConversations()

        viewModel.deleteConversation(ConversationId("1"))

        assertTrue(fakeConvRepo.deletedConversationIds.contains("1"))
        assertTrue(viewModel.uiState.value.conversations.none { it.conversation.id == ConversationId("1") })
    }

    @Test
    fun `openConversationAdmin loads selected conversation`() = runTest {
        val display = ConversationDisplay(TestData.conversation(id = "1", agentId = "a1"), "Agent One")

        viewModel.openConversationAdmin(display)

        assertEquals(ConversationId("1"), viewModel.uiState.value.selectedConversation?.conversation?.id)
        assertEquals(1, viewModel.uiState.value.inspectorMessages.size)
    }

    @Test
    fun `setConversationArchived updates state`() = runTest {
        val conversation = TestData.conversation(id = "1", agentId = "a1").copy(archived = false)
        fakeAllRepo.setConversations(listOf(conversation))
        viewModel.loadConversations()
        val display = viewModel.uiState.value.conversations.first()

        viewModel.setConversationArchived(display, true)

        assertTrue(fakeConvRepo.archivedUpdates.contains("1" to true))
        assertTrue(viewModel.uiState.value.conversations.first().conversation.archived == true)
    }

    @Test
    fun `recompileConversation stores preview`() = runTest {
        val display = ConversationDisplay(TestData.conversation(id = "1", agentId = "a1"), "Agent One")

        viewModel.recompileConversation(display)

        assertEquals("recompiled-system-prompt", viewModel.uiState.value.recompilePreview)
    }

    @Test
    fun `closeConversationAdmin clears inspector state`() = runTest {
        val display = ConversationDisplay(TestData.conversation(id = "1", agentId = "a1"), "Agent One")

        viewModel.openConversationAdmin(display)
        viewModel.closeConversationAdmin()

        assertEquals(0, viewModel.uiState.value.inspectorMessages.size)
        assertEquals(null, viewModel.uiState.value.selectedConversation)
    }

    @Test
    fun `openConversationAdmin keeps dialog open when inspector load fails`() = runTest {
        fakeMessageRepo.shouldFail = true
        val display = ConversationDisplay(TestData.conversation(id = "1", agentId = "a1"), "Agent One")

        viewModel.openConversationAdmin(display)

        assertEquals(ConversationId("1"), viewModel.uiState.value.selectedConversation?.conversation?.id)
        assertEquals(0, viewModel.uiState.value.inspectorMessages.size)
        assertEquals("Inspector failed", viewModel.uiState.value.inspectorError)
    }

    private fun newViewModel(): ConversationsViewModel = ConversationsViewModel(
        fakeAllRepo,
        fakeConvRepo,
        fakeAgentRepo,
        fakeMessageRepo,
        settingsRepository,
        FakeEmbeddedRuntimeStatusProvider(),
        FakeEmbeddedModelRepository(),
    )

    private fun localConfig(): LettaConfig = LettaConfig(
        id = "local",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://runtime",
    )

    private fun remoteConfig(): LettaConfig = LettaConfig(
        id = "remote",
        mode = LettaConfig.Mode.CLOUD,
        serverUrl = "https://api.letta.example",
    )

    private fun localAgent(id: String, name: String): Agent = Agent(
        id = AgentId(id),
        name = name,
        metadata = mapOf(LocalAgentRuntimeMetadata.RuntimeKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime)),
    )

    private class FakeAllConversationsRepository : AllConversationsRepository(FakeConversationApi()) {
        private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
        override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
        var fresh: Boolean = false
        var didRefresh: Boolean = false
        var refreshError: Throwable? = null

        fun setConversations(list: List<Conversation>) { _conversations.value = list }
        override suspend fun refresh() { didRefresh = true }
        override suspend fun refreshIfStale(maxAgeMs: Long): Boolean {
            refreshError?.let { throw it }
            if (fresh) return false
            didRefresh = true
            return true
        }
        override fun handleOptimisticDelete(conversationId: ConversationId) {
            _conversations.value = _conversations.value.filter { it.id != conversationId }
        }
        override fun handleOptimisticUpdate(conversation: Conversation) {
            _conversations.value = _conversations.value + conversation
        }
    }

    private class FakeConversationRepository(
        agentRepository: AgentRepository,
    ) : ConversationRepository(FakeConversationApi(), agentRepository, mockk(relaxed = true)) {
        val deletedConversationIds = mutableListOf<String>()
        val archivedUpdates = mutableListOf<Pair<String, Boolean>>()

        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
            return TestData.conversation(id = "new-conv", agentId = agentId.value)
        }

        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) {
            deletedConversationIds.add(id.value)
        }

        override suspend fun getConversation(id: ConversationId): Conversation {
            return TestData.conversation(id = id.value, agentId = "a1")
        }

        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) {
            archivedUpdates.add(id.value to archived)
        }

        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String {
            return "recompiled-system-prompt"
        }

        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) {}
    }

    private class FakeAgentRepository : AgentRepository(FakeAgentApi(), mockk(relaxed = true)) {
        private val _agents = MutableStateFlow(listOf(Agent(id = AgentId("a1"), name = "Agent One")))
        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        fun setAgents(list: List<Agent>) { _agents.value = list }
        private val _refreshError = MutableStateFlow<Throwable?>(null)
        override val refreshError: StateFlow<Throwable?> = _refreshError.asStateFlow()
        var fresh: Boolean = false
        var didRefresh: Boolean = false
        var refreshFailure: Throwable? = null
        override suspend fun refreshAgents() { didRefresh = true }
        override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
            refreshFailure?.let { error ->
                _refreshError.value = error
                throw error
            }
            if (fresh) return false
            _refreshError.value = null
            didRefresh = true
            return true
        }
        override fun getAgent(id: AgentId) = flow { emit(_agents.value.first()) }
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams) = _agents.value.first()
        override suspend fun updateAgent(id: AgentId, params: com.letta.mobile.data.model.AgentUpdateParams) = _agents.value.first()
        override suspend fun deleteAgent(id: AgentId) {}
    }


    private class FakeEmbeddedRuntimeStatusProvider : EmbeddedLettaCodeRuntimeStatusProvider {
        override val status: EmbeddedLettaCodeRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
            nativeEnabled = true,
            assetsEnabled = true,
            version = "test",
            integrity = "test",
        )
    }

    private class FakeEmbeddedModelRepository : EmbeddedModelRepository {
        private val catalogState = MutableStateFlow<List<EmbeddedModelCatalogItem>>(emptyList())
        override val catalog: StateFlow<List<EmbeddedModelCatalogItem>> = catalogState.asStateFlow()
        override suspend fun refresh() = Unit
        override suspend fun download(entry: EmbeddedModelCatalogEntry) = Unit
        override fun cancel(entry: EmbeddedModelCatalogEntry) = Unit
        override fun localPathFor(entry: EmbeddedModelCatalogEntry): String? = null
    }

    private class FakeMessageRepository : MessageRepository(FakeMessageApi()) {
        var shouldFail: Boolean = false

        override suspend fun fetchConversationInspectorMessages(conversationId: ConversationId): List<ConversationInspectorMessage> {
            if (shouldFail) throw IllegalStateException("Inspector failed")
            return listOf(
                ConversationInspectorMessage(
                    id = "msg-1",
                    messageType = "assistant_message",
                    date = "2026-04-09T10:00:00Z",
                    runId = "run-1",
                    stepId = null,
                    otid = null,
                    summary = "Hello from the inspector",
                    detailLines = listOf("Run ID" to "run-1"),
                )
            )
        }
    }
}
