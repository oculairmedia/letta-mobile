package com.letta.mobile.ui.screens.conversations

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private lateinit var fakeAllRepo: FakeAllConversationsRepository
    private lateinit var fakeConvRepo: FakeConversationRepository
    private lateinit var fakeAgentRepo: FakeAgentRepository
    private lateinit var viewModel: ConversationsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAllRepo = FakeAllConversationsRepository()
        fakeConvRepo = FakeConversationRepository()
        fakeAgentRepo = FakeAgentRepository()
        viewModel = ConversationsViewModel(fakeAllRepo, fakeConvRepo, fakeAgentRepo)
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

    private class FakeAllConversationsRepository : AllConversationsRepository(FakeConversationApi()) {
        private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
        override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

        fun setConversations(list: List<Conversation>) { _conversations.value = list }
        override suspend fun refresh() {}
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

    private class FakeAgentRepository : AgentRepository(FakeAgentApi()) {
        private val _agents = MutableStateFlow(listOf(Agent(id = "a1", name = "Agent One")))
        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        override suspend fun refreshAgents() {}
        override fun getAgent(id: String) = flow { emit(_agents.value.first()) }
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams) = _agents.value.first()
        override suspend fun updateAgent(id: String, params: com.letta.mobile.data.model.AgentUpdateParams) = _agents.value.first()
        override suspend fun deleteAgent(id: String) {}
    }
}
