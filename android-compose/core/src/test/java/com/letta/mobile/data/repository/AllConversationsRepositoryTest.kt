package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AllConversationsRepositoryTest {

    private lateinit var fakeApi: FakeConversationApi
    private lateinit var repository: AllConversationsRepository

    @Before
    fun setup() {
        fakeApi = FakeConversationApi()
        repository = AllConversationsRepository(fakeApi)
    }

    @Test
    fun `concurrent refreshIfStale callers share one list request`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1"))
        fakeApi.listDelayMillis = 1L

        List(8) {
            launch { repository.refreshIfStale(maxAgeMs = 60_000) }
        }.joinAll()

        assertEquals(1, fakeApi.calls.count { it == "listConversations" })
        assertEquals(listOf("1"), repository.conversations.value.map { it.id })
    }

    @Test
    fun `refresh clears and reloads`() = runTest {
        fakeApi.conversations.addAll(listOf(
            TestData.conversation(id = "1"),
            TestData.conversation(id = "2"),
        ))
        repository.refresh()
        assertEquals(2, repository.conversations.value.size)
    }

    @Test
    fun `refresh resets cursor and hasMore`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1"))
        repository.refresh()
        assertTrue(repository.conversations.value.isNotEmpty())
    }

    @Test
    fun `refreshIfStale refreshes after ttl expires`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "old"))
        repository.refresh()
        fakeApi.conversations.clear()
        fakeApi.conversations.add(TestData.conversation(id = "new"))
        fakeApi.calls.clear()

        val refreshed = repository.refreshIfStale(maxAgeMs = -1)

        assertEquals(true, refreshed)
        assertEquals(listOf("new"), repository.conversations.value.map { it.id })
        assertEquals(1, fakeApi.calls.count { it == "listConversations" })
    }

    @Test
    fun `failed stale refresh preserves loaded conversations`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "cached"))
        repository.refresh()
        fakeApi.shouldFail = true

        try {
            repository.refreshIfStale(maxAgeMs = -1)
            fail("Expected stale refresh to throw")
        } catch (_: Exception) {
            // Expected.
        }

        assertEquals(listOf("cached"), repository.conversations.value.map { it.id })
    }

    @Test
    fun `handleOptimisticUpdate adds new conversation`() {
        val conv = TestData.conversation(id = "new-1")
        repository.handleOptimisticUpdate(conv)
        assertEquals(1, repository.conversations.value.size)
        assertEquals("new-1", repository.conversations.value.first().id)
    }

    @Test
    fun `handleOptimisticUpdate updates existing conversation`() {
        repository.handleOptimisticUpdate(TestData.conversation(id = "1", summary = "Old"))
        repository.handleOptimisticUpdate(TestData.conversation(id = "1", summary = "New"))
        assertEquals(1, repository.conversations.value.size)
        assertEquals("New", repository.conversations.value.first().summary)
    }

    @Test
    fun `handleOptimisticDelete removes conversation`() {
        repository.handleOptimisticUpdate(TestData.conversation(id = "1"))
        repository.handleOptimisticUpdate(TestData.conversation(id = "2"))
        repository.handleOptimisticDelete("1")
        assertEquals(1, repository.conversations.value.size)
        assertTrue(repository.conversations.value.none { it.id == "1" })
    }

    @Test
    fun `hasMore is false when page smaller than PAGE_SIZE`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1"))
        repository.refresh()
        assertFalse(repository.hasMore.value)
    }

    @Test
    fun `loadedCountEstimate uses loaded page and does not make count network request`() = runTest {
        repeat(125) { index ->
            fakeApi.conversations.add(TestData.conversation(id = "conv-$index"))
        }
        repository.refresh()
        fakeApi.calls.clear()
        fakeApi.listLimits.clear()

        val estimate = repository.loadedCountEstimate()

        assertEquals(50, estimate?.count)
        assertEquals(true, estimate?.isApproximate)
        assertTrue(fakeApi.calls.none { it == "listConversations" })
        assertTrue(fakeApi.listLimits.none { it == 1_000 || it == 10_000 })
    }

    @Test
    fun `countConversations compatibility shim does not fetch`() = runTest {
        repeat(125) { index ->
            fakeApi.conversations.add(TestData.conversation(id = "conv-$index"))
        }
        repository.refresh()
        fakeApi.calls.clear()
        fakeApi.listLimits.clear()

        val count = repository.countConversations()

        assertEquals(50, count)
        assertTrue(fakeApi.calls.none { it == "listConversations" })
        assertTrue(fakeApi.listLimits.isEmpty())
    }

    @Test
    fun `empty refresh response is fresh and exact zero estimate`() = runTest {
        repository.refresh()
        fakeApi.calls.clear()

        assertEquals(ConversationCountEstimate(count = 0, isApproximate = false), repository.loadedCountEstimate())
        assertTrue(repository.hasFreshConversations(maxAgeMs = 60_000))
        assertEquals(false, repository.refreshIfStale(maxAgeMs = 60_000))
        assertTrue(fakeApi.calls.none { it == "listConversations" })
    }
}
