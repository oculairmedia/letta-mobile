package com.letta.mobile.ui.screens.conversations

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.testutil.FakeMessageApi
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import io.mockk.mockk
import io.mockk.every
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
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private lateinit var fakeAllRepo: FakeAllConversationsRepository
    private lateinit var fakeConvRepo: FakeConversationRepository
    private lateinit var fakeAgentRepo: FakeAgentRepository
    private lateinit var fakeMessageRepo: FakeMessageRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ConversationsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()
    private val pinnedConversationIds = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAllRepo = FakeAllConversationsRepository()
        fakeConvRepo = FakeConversationRepository()
        fakeAgentRepo = FakeAgentRepository()
        fakeMessageRepo = FakeMessageRepository()
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.getPinnedConversationIds() } returns pinnedConversationIds
        viewModel = ConversationsViewModel(
            fakeAllRepo,
            fakeConvRepo,
            fakeAgentRepo,
            fakeMessageRepo,
            settingsRepository,
        )
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
    fun `deleteConversation delegates to repository and removes from state`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1", agentId = "a1")))
        viewModel.loadConversations()

        viewModel.deleteConversation("1")

        assertTrue(fakeConvRepo.deletedConversationIds.contains("1"))
        assertTrue(viewModel.uiState.value.conversations.none { it.conversation.id == "1" })
    }

    @Test
    fun `openConversationAdmin loads selected conversation`() = runTest {
        val display = ConversationDisplay(TestData.conversation(id = "1", agentId = "a1"), "Agent One")

        viewModel.openConversationAdmin(display)

        assertEquals("1", viewModel.uiState.value.selectedConversation?.conversation?.id)
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

        assertEquals("1", viewModel.uiState.value.selectedConversation?.conversation?.id)
        assertEquals(0, viewModel.uiState.value.inspectorMessages.size)
        assertEquals("Inspector failed", viewModel.uiState.value.inspectorError)
    }

    private class FakeAllConversationsRepository : AllConversationsRepository(FakeConversationApi()) {
        private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
        override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
        var fresh: Boolean = false
        var didRefresh: Boolean = false

        fun setConversations(list: List<Conversation>) { _conversations.value = list }
        override suspend fun refresh() { didRefresh = true }
        override suspend fun refreshIfStale(maxAgeMs: Long): Boolean {
            if (fresh) return false
            didRefresh = true
            return true
        }
        override fun handleOptimisticDelete(conversationId: String) {
            _conversations.value = _conversations.value.filter { it.id != conversationId }
        }
        override fun handleOptimisticUpdate(conversation: Conversation) {
            _conversations.value = _conversations.value + conversation
        }
    }

    private class FakeConversationRepository : ConversationRepository(FakeConversationApi()) {
        val deletedConversationIds = mutableListOf<String>()
        val archivedUpdates = mutableListOf<Pair<String, Boolean>>()

        override suspend fun createConversation(agentId: String, summary: String?): Conversation {
            return TestData.conversation(id = "new-conv", agentId = agentId)
        }

        override suspend fun deleteConversation(id: String, agentId: String) {
            deletedConversationIds.add(id)
        }

        override suspend fun getConversation(id: String): Conversation {
            return TestData.conversation(id = id, agentId = "a1")
        }

        override suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean) {
            archivedUpdates.add(id to archived)
        }

        override suspend fun recompileConversation(id: String, dryRun: Boolean, agentId: String?): String {
            return "recompiled-system-prompt"
        }

        override suspend fun cancelConversation(id: String, agentId: String?) {}
    }

    private class FakeAgentRepository : AgentRepository(FakeAgentApi(), mockk(relaxed = true)) {
        private val _agents = MutableStateFlow(listOf(Agent(id = "a1", name = "Agent One")))
        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        var fresh: Boolean = false
        var didRefresh: Boolean = false
        override suspend fun refreshAgents() { didRefresh = true }
        override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
            if (fresh) return false
            didRefresh = true
            return true
        }
        override fun getAgent(id: String) = flow { emit(_agents.value.first()) }
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams) = _agents.value.first()
        override suspend fun updateAgent(id: String, params: com.letta.mobile.data.model.AgentUpdateParams) = _agents.value.first()
        override suspend fun deleteAgent(id: String) {}
    }

    private class FakeMessageRepository : MessageRepository(FakeMessageApi(), mockk(relaxed = true)) {
        var shouldFail: Boolean = false

        override suspend fun fetchConversationInspectorMessages(conversationId: String): List<ConversationInspectorMessage> {
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
