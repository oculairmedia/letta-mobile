package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ChatSearchCoordinatorTest {

    @Test
    fun `updateQuery with blank string clears state`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val repo = mockk<MessageRepository>(relaxed = true)
        val uiState = MutableStateFlow(
            ChatUiState(
                searchQuery = "old",
                isSearchActive = true,
                isSearching = true,
                searchResults = persistentListOf(mockk())
            )
        )

        val coordinator = ChatSearchCoordinator(
            scope = scope,
            messageRepository = repo,
            uiState = uiState,
            agentId = "agent1",
            conversationId = { "conv1" }
        )

        coordinator.updateQuery("   ")

        val state = uiState.value
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertFalse(state.isSearching)
        assertTrue(state.searchResults.isEmpty())
    }

    @Test
    fun `updateQuery immediately updates local results and starts search`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val repo = mockk<MessageRepository>(relaxed = true)

        val localMessage = UiMessage(
            id = "msg1",
            role = "user",
            content = "hello world",
            timestamp = "2024"
        )
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(localMessage)))

        val coordinator = ChatSearchCoordinator(
            scope = scope,
            messageRepository = repo,
            uiState = uiState,
            agentId = "agent1",
            conversationId = { "conv1" }
        )

        coordinator.updateQuery("hello")

        val state = uiState.value
        assertEquals("hello", state.searchQuery)
        assertTrue(state.isSearchActive)
        assertTrue(state.isSearching)
        assertEquals(1, state.searchResults.size)
        assertEquals("hello world", state.searchResults.first().content)
        assertEquals("msg1", state.searchResults.first().messageId)
    }

    @Test
    fun `remote debounce updates results and clears searching flag`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val repo = mockk<MessageRepository>()
        
        coEvery { repo.searchMessages(any()) } returns listOf(
            MessageSearchResult(
                embeddedText = "remote hello",
                message = buildJsonObject {
                    put("id", "msg2")
                    put("agent_id", "agent1")
                    put("role", "assistant")
                    put("text", "remote hello")
                    put("date", "2024")
                    put("conversation_id", "conv1")
                }
            )
        )

        val localMessage = UiMessage(
            id = "msg1",
            role = "user",
            content = "hello world",
            timestamp = "2024"
        )
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(localMessage)))

        val coordinator = ChatSearchCoordinator(
            scope = scope,
            messageRepository = repo,
            uiState = uiState,
            agentId = "agent1",
            conversationId = { "conv1" },
            remoteDebounceMs = 180L
        )

        coordinator.updateQuery("hello")
        
        // Before delay, only local
        assertEquals(1, uiState.value.searchResults.size)
        assertTrue(uiState.value.isSearching)

        // Advance time
        advanceTimeBy(180L)
        runCurrent()

        // After delay, both local and remote
        val state = uiState.value
        assertFalse(state.isSearching)
        assertEquals(2, state.searchResults.size)
        assertEquals("hello world", state.searchResults[0].content)
        assertEquals("remote hello", state.searchResults[1].content)
        
        coVerify(exactly = 1) { repo.searchMessages(any()) }
    }

    @Test
    fun `concurrent queries cancel previous debounce`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val repo = mockk<MessageRepository>()
        
        coEvery { repo.searchMessages(any()) } returns emptyList()

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ChatSearchCoordinator(
            scope = scope,
            messageRepository = repo,
            uiState = uiState,
            agentId = "agent1",
            conversationId = { "conv1" },
            remoteDebounceMs = 180L
        )

        coordinator.updateQuery("first")
        advanceTimeBy(100L)
        runCurrent() // Partial delay
        coordinator.updateQuery("second")
        advanceTimeBy(180L)
        runCurrent() // Full delay for second

        // Only "second" should have fired remotely
        coVerify(exactly = 1) { 
            repo.searchMessages(match { it.query == "second" })
        }
        coVerify(exactly = 0) { 
            repo.searchMessages(match { it.query == "first" })
        }
    }

    @Test
    fun `remote query failure updates isSearching safely`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val repo = mockk<MessageRepository>()
        
        coEvery { repo.searchMessages(any()) } throws RuntimeException("Network Error")

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ChatSearchCoordinator(
            scope = scope,
            messageRepository = repo,
            uiState = uiState,
            agentId = "agent1",
            conversationId = { "conv1" },
            remoteDebounceMs = 180L
        )

        try {
            coordinator.updateQuery("hello")
            advanceTimeBy(180L)
        runCurrent()
        } catch (e: Exception) {
            // gracefully handle android log missing in standard jvm tests
        }

        val state = uiState.value
        assertFalse(state.isSearching)
        assertEquals("hello", state.searchQuery)
    }

    @Test
    fun `clear resets state`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val uiState = MutableStateFlow(
            ChatUiState(
                searchQuery = "old",
                isSearchActive = true,
                isSearching = true,
                searchResults = persistentListOf(mockk())
            )
        )

        val coordinator = ChatSearchCoordinator(
            scope = scope,
            messageRepository = mockk(),
            uiState = uiState,
            agentId = "agent1",
            conversationId = { "conv1" }
        )

        coordinator.clear()

        val state = uiState.value
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertFalse(state.isSearching)
        assertTrue(state.searchResults.isEmpty())
    }
}
