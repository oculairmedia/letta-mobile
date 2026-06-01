package com.letta.mobile.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeAgentRepository
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ConversationRepositoryTest {

    private lateinit var fakeApi: FakeConversationApi
    private lateinit var repository: ConversationRepository
    private lateinit var database: LettaDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApi = FakeConversationApi()
        val agentRepository = FakeAgentRepository()
        repository = ConversationRepository(fakeApi, agentRepository, database.conversationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `concurrent refreshConversationsIfStale callers share one list request`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1"))
        fakeApi.listDelayMillis = 1L

        List(8) {
            launch { repository.refreshConversationsIfStale("a1", maxAgeMs = 60_000) }
        }.joinAll()

        assertEquals(1, fakeApi.calls.count { it == "listConversations" })
        assertEquals(listOf("1"), repository.getConversations("a1").first().map { it.id.value })
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
    fun `getConversations emits cached Room rows before network`() = runTest {
        val cached = TestData.conversation(id = "cached-1", agentId = "a1", summary = "Cached title")
        database.conversationDao().upsert(ConversationEntity.fromConversation(cached, cachedAtEpochMs = 10L))
        database.conversationDao().upsertRefreshState(
            ConversationRefreshEntity(agentId = "a1", lastRefreshAtMillis = System.currentTimeMillis()),
        )
        fakeApi.listDelayMillis = 1_000L

        val result = repository.getConversations("a1").first()

        assertEquals(listOf("Cached title"), result.map { it.summary })
        assertTrue(fakeApi.calls.none { it == "listConversations" })
    }

    @Test
    fun `empty refresh response is cached as fresh`() = runTest {
        repository.refreshConversations("a1")
        fakeApi.calls.clear()

        val refreshed = repository.refreshConversationsIfStale("a1", maxAgeMs = 60_000)

        assertEquals(false, refreshed)
        assertTrue(repository.getConversations("a1").first().isEmpty())
        assertTrue(fakeApi.calls.none { it == "listConversations" })
    }

    @Test
    fun `refreshConversationsIfStale skips fresh cache`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1"))

        repository.refreshConversations("a1")
        fakeApi.calls.clear()

        repository.refreshConversationsIfStale("a1", maxAgeMs = 60_000)

        assertTrue(fakeApi.calls.none { it == "listConversations" })
    }

    @Test
    fun `refreshConversationsIfStale refreshes stale cache`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "old", agentId = "a1"))
        repository.refreshConversations("a1")
        fakeApi.conversations.clear()
        fakeApi.conversations.add(TestData.conversation(id = "new", agentId = "a1"))
        fakeApi.calls.clear()

        val refreshed = repository.refreshConversationsIfStale("a1", maxAgeMs = -1)

        assertEquals(true, refreshed)
        assertEquals(listOf("new"), repository.getConversations("a1").first().map { it.id.value })
        assertEquals(1, fakeApi.calls.count { it == "listConversations" })
    }

    @Test
    fun `failed stale refresh preserves cached conversations`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "cached", agentId = "a1"))
        repository.refreshConversations("a1")
        fakeApi.shouldFail = true

        try {
            repository.refreshConversationsIfStale("a1", maxAgeMs = -1)
            fail("Expected stale refresh to throw")
        } catch (_: Exception) {
            // Expected.
        }

        assertEquals(listOf("cached"), repository.getConversations("a1").first().map { it.id.value })
    }

    @Test
    fun `createConversation adds to list and returns new conversation`() = runTest {
        val conv = repository.createConversation("a1", "Test summary")

        assertEquals("a1", conv.agentId.value)
        assertTrue(fakeApi.calls.any { it.startsWith("createConversation") })
        assertTrue(repository.getConversations("a1").first().any { it.id == conv.id })
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

        assertTrue(result.none { it.id.value == "1" })
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

        assertTrue(forked.id.value.startsWith("fork-"))
        assertTrue(fakeApi.calls.any { it.startsWith("forkConversation") })
        assertTrue(repository.getConversations("a1").first().any { it.id == forked.id })
    }

    @Test
    fun `getConversation retrieves a conversation by id`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1"))

        val conversation = repository.getConversation("1")

        assertEquals("1", conversation.id.value)
    }

    @Test
    fun `setConversationArchived updates archived state optimistically`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1", summary = "Old").copy(archived = false))
        repository.refreshConversations("a1")

        repository.setConversationArchived("1", "a1", true)
        val result = repository.getConversations("a1").first()

        assertTrue(result.first().archived == true)
    }

    @Test
    fun `cancelConversation delegates to api`() = runTest {
        repository.cancelConversation("1", "a1")
        assertTrue(fakeApi.calls.contains("cancelConversation:1"))
    }

    @Test
    fun `recompileConversation delegates to api`() = runTest {
        val result = repository.recompileConversation("1", false, "a1")
        assertEquals("recompiled-system-prompt", result)
        assertTrue(fakeApi.calls.contains("recompileConversation:1"))
    }

    @Test
    fun `getConversations returns empty for unknown agent`() = runTest {
        val result = repository.getConversations("unknown").first()
        assertTrue(result.isEmpty())
    }
}
