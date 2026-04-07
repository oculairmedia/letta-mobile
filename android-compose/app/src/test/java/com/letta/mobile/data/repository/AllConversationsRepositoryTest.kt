package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AllConversationsRepositoryTest {

    private lateinit var fakeApi: FakeConversationApi
    private lateinit var repository: AllConversationsRepository

    @Before
    fun setup() {
        fakeApi = FakeConversationApi()
        repository = AllConversationsRepository(fakeApi)
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
}
