package com.letta.mobile.ui.screens.conversations

import app.cash.turbine.test
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private lateinit var viewModel: ConversationsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAllRepo = FakeAllConversationsRepository()
        fakeConvRepo = FakeConversationRepository()
        viewModel = ConversationsViewModel(fakeAllRepo, fakeConvRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadConversations sets Success with conversations`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1"), TestData.conversation(id = "2")))
        viewModel.loadConversations()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            assertEquals(2, (state as UiState.Success).data.conversations.size)
        }
    }

    @Test
    fun `loadConversations sets Error on failure`() = runTest {
        fakeAllRepo.shouldFail = true
        viewModel.loadConversations()
        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
    }

    @Test
    fun `deleteConversation removes from list`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1"), TestData.conversation(id = "2")))
        viewModel.loadConversations()
        viewModel.deleteConversation("1")
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.conversations.none { it.id == "1" })
        }
    }

    @Test
    fun `createConversation invokes onSuccess`() = runTest {
        var capturedId = ""
        viewModel.createConversation("a1") { capturedId = it }
        assertTrue(capturedId.isNotEmpty())
    }

    @Test
    fun `updateSearchQuery updates state`() = runTest {
        fakeAllRepo.setConversations(listOf(TestData.conversation(id = "1")))
        viewModel.loadConversations()
        viewModel.updateSearchQuery("test")
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("test", state.data.searchQuery)
        }
    }

    private class FakeAllConversationsRepository : AllConversationsRepository(FakeConversationApi()) {
        private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
        override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
        var shouldFail = false

        fun setConversations(list: List<Conversation>) { _conversations.value = list }
        override suspend fun refresh() { if (shouldFail) throw Exception("Refresh failed") }
        override fun handleOptimisticDelete(conversationId: String) {
            _conversations.value = _conversations.value.filter { it.id != conversationId }
        }
        override fun handleOptimisticUpdate(conversation: Conversation) {
            _conversations.value = _conversations.value + conversation
        }
    }

    private class FakeConversationRepository : ConversationRepository(FakeConversationApi()) {
        override suspend fun createConversation(agentId: String, summary: String?): Conversation {
            return TestData.conversation(id = "new-conv", agentId = agentId)
        }
    }
}
