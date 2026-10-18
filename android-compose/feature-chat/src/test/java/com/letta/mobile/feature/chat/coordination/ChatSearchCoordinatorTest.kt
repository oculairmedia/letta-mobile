package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.search.MessageSearchMode
import com.letta.mobile.data.search.MessageSearchSelection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchCoordinatorTest {

    @Test
    fun `updateQuery with blank string clears state`() = runTest {
        val uiState = MutableStateFlow(populatedSearchState())
        val coordinator = newCoordinator(uiState = uiState)

        coordinator.updateQuery("   ")

        assertSearchCleared(uiState.value)
    }

    @Test
    fun `updateQuery immediately updates local results and starts search`() = runTest {
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(localHelloMessage())))
        val coordinator = newCoordinator(uiState = uiState)

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
        val repo = mockk<IMessageRepository>()
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
                },
            ),
        )
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(localHelloMessage())))
        val coordinator = newCoordinator(repo = repo, uiState = uiState)

        coordinator.updateQuery("hello")

        assertEquals(1, uiState.value.searchResults.size)
        assertTrue(uiState.value.isSearching)

        advanceTimeBy(180L)
        runCurrent()

        val state = uiState.value
        assertFalse(state.isSearching)
        assertEquals(2, state.searchResults.size)
        assertEquals("hello world", state.searchResults[0].content)
        assertEquals("remote hello", state.searchResults[1].content)
        coVerify(exactly = 1) { repo.searchMessages(any()) }
    }

    @Test
    fun `concurrent queries cancel previous debounce`() = runTest {
        val repo = mockk<IMessageRepository>()
        coEvery { repo.searchMessages(any()) } returns emptyList()
        val uiState = MutableStateFlow(ChatUiState())
        val coordinator = newCoordinator(repo = repo, uiState = uiState)

        coordinator.updateQuery("first")
        advanceTimeBy(100L)
        runCurrent()
        coordinator.updateQuery("second")
        advanceTimeBy(180L)
        runCurrent()

        assertEquals("second", uiState.value.searchQuery)
        assertFalse(uiState.value.isSearching)
        coVerify(exactly = 1) { repo.searchMessages(match { it.query == "second" }) }
        coVerify(exactly = 0) { repo.searchMessages(match { it.query == "first" }) }
    }

    @Test
    fun `picking a mode chip runs the live query again in that mode`() = runTest {
        val repo = mockk<IMessageRepository>()
        coEvery { repo.searchMessages(any()) } returns emptyList()
        val uiState = MutableStateFlow(ChatUiState())
        val coordinator = newCoordinator(repo = repo, uiState = uiState)

        coordinator.updateQuery("deploy")
        advanceTimeBy(180L)
        runCurrent()
        coordinator.updateSelection(MessageSearchSelection(mode = MessageSearchMode.Semantic))
        advanceTimeBy(180L)
        runCurrent()

        coVerify(exactly = 1) { repo.searchMessages(match { it.query == "deploy" && it.searchMode == "fts" }) }
        coVerify(exactly = 1) { repo.searchMessages(match { it.query == "deploy" && it.searchMode == "vector" }) }
        assertEquals(MessageSearchMode.Semantic, uiState.value.searchSelection.mode)
    }

    @Test
    fun `remote query failure updates isSearching safely`() = runTest {
        val repo = mockk<IMessageRepository>()
        coEvery { repo.searchMessages(any()) } throws RuntimeException("Network Error")
        val uiState = MutableStateFlow(ChatUiState())
        val coordinator = newCoordinator(repo = repo, uiState = uiState)

        try {
            coordinator.updateQuery("hello")
            advanceTimeBy(180L)
            runCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // gracefully handle android log missing in standard jvm tests
        }

        val state = uiState.value
        assertFalse(state.isSearching)
        assertEquals("hello", state.searchQuery)
    }

    @Test
    fun `clear resets state`() = runTest {
        val uiState = MutableStateFlow(populatedSearchState())
        val coordinator = newCoordinator(uiState = uiState)

        coordinator.clear()

        assertSearchCleared(uiState.value)
    }

    private fun TestScope.newCoordinator(
        repo: IMessageRepository = mockk(relaxed = true),
        uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState()),
    ): ChatSearchCoordinator = ChatSearchCoordinator(
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        messageRepository = repo,
        uiState = uiState,
        agentId = "agent1",
        conversationId = { "conv1" },
        remoteDebounceMs = 180L,
    )

    private fun populatedSearchState(): ChatUiState = ChatUiState(
        searchQuery = "old",
        isSearchActive = true,
        isSearching = true,
        searchResults = persistentListOf(mockk()),
    )

    private fun localHelloMessage(): UiMessage = UiMessage(
        id = "msg1",
        role = "user",
        content = "hello world",
        timestamp = "2024",
    )

    private fun assertSearchCleared(state: ChatUiState) {
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertFalse(state.isSearching)
        assertTrue(state.searchResults.isEmpty())
    }
}
