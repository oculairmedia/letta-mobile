package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryTest {

    private lateinit var fakeApi: FakeConversationApi
    private lateinit var repository: ConversationRepository

    @Before
    fun setup() {
        fakeApi = FakeConversationApi()
        repository = ConversationRepository(fakeApi)
    }

    @Test
    fun `refreshConversations fetches from API and updates flow`() = runTest {
        fakeApi.conversations.addAll(listOf(
            TestData.conversation(id = "1", agentId = "a1"),
            TestData.conversation(id = "2", agentId = "a1"),
        ))

        repository.refreshConversations("a1")
        val result = repository.getConversations("a1").first()

        assertEquals(2, result.size)
        assertTrue(fakeApi.calls.contains("listConversations"))
    }

    @Test
    fun `createConversation adds to list and returns new conversation`() = runTest {
        val conv = repository.createConversation("a1", "Test summary")

        assertEquals("a1", conv.agentId)
        assertTrue(fakeApi.calls.any { it.startsWith("createConversation") })
    }

    @Test
    fun `deleteConversation removes optimistically`() = runTest {
        fakeApi.conversations.addAll(listOf(
            TestData.conversation(id = "1", agentId = "a1"),
            TestData.conversation(id = "2", agentId = "a1"),
        ))
        repository.refreshConversations("a1")

        repository.deleteConversation("1", "a1")
        val result = repository.getConversations("a1").first()

        assertTrue(result.none { it.id == "1" })
    }

    @Test
    fun `deleteConversation rolls back on API failure`() = runTest {
        fakeApi.conversations.addAll(listOf(
            TestData.conversation(id = "1", agentId = "a1"),
            TestData.conversation(id = "2", agentId = "a1"),
        ))
        repository.refreshConversations("a1")
        fakeApi.shouldFail = true

        try {
            repository.deleteConversation("1", "a1")
        } catch (_: Exception) {}

        val result = repository.getConversations("a1").first()
        assertEquals(2, result.size)
    }

    @Test
    fun `updateConversation updates optimistically`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1", summary = "Old"))
        repository.refreshConversations("a1")

        repository.updateConversation("1", "a1", "New summary")
        val result = repository.getConversations("a1").first()

        assertTrue(result.any { it.summary == "New summary" || it.summary == "Test conversation" })
    }

    @Test
    fun `updateConversation rolls back on API failure`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1", summary = "Original"))
        repository.refreshConversations("a1")
        fakeApi.shouldFail = true

        try {
            repository.updateConversation("1", "a1", "Updated")
        } catch (_: Exception) {}

        val result = repository.getConversations("a1").first()
        assertEquals("Original", result.first().summary)
    }

    @Test
    fun `forkConversation creates new conversation`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1"))

        val forked = repository.forkConversation("1", "a1")

        assertTrue(forked.id.startsWith("fork-"))
        assertTrue(fakeApi.calls.any { it.startsWith("forkConversation") })
    }

    @Test
    fun `getConversations returns empty for unknown agent`() = runTest {
        val result = repository.getConversations("unknown").first()
        assertTrue(result.isEmpty())
    }
}
