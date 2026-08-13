package com.letta.mobile.data.repository

import androidx.paging.PagingSource
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import com.letta.mobile.testutil.armedConversationApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        assertEquals(listOf("1"), repository.conversations.value.map { it.id.value })
    }

    // letta-mobile-ajtu2: local-runtime mode routes refreshes to the
    // on-device letta.js store instead of the remote API, inside the
    // repository so ViewModels need no per-screen branching.
    @Test
    fun `refresh uses local source when active config is local runtime`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "remote-1"))
        val localConversation = TestData.conversation(id = "local-conv-agent-1", agentId = "agent-1")
        val localRepository = AllConversationsRepository(
            conversationApi = fakeApi,
            conversationDao = null,
            localConversationSource = FakeLocalRuntimeConversationSource {
                listOf(localConversation)
            },
            settingsRepository = FakeSettingsRepository(
                initialActiveConfig = LettaConfig(
                    id = "local-1",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local-lettacode://device",
                ),
            ),
        )

        localRepository.refresh()

        assertEquals(listOf("local-conv-agent-1"), localRepository.conversations.value.map { it.id.value })
        assertEquals(0, fakeApi.calls.count { it == "listConversations" })
        assertFalse(localRepository.hasMore.value)
    }

    @Test
    fun `refresh uses remote api when active config is not local`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "remote-1"))
        val repositoryWithLocal = AllConversationsRepository(
            conversationApi = fakeApi,
            conversationDao = null,
            localConversationSource = FakeLocalRuntimeConversationSource {
                throw AssertionError("local source must not be used for remote configs")
            },
            settingsRepository = FakeSettingsRepository(
                initialActiveConfig = LettaConfig(
                    id = "remote-1",
                    mode = LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = "https://letta.example.dev",
                ),
            ),
        )

        repositoryWithLocal.refresh()

        assertEquals(listOf("remote-1"), repositoryWithLocal.conversations.value.map { it.id.value })
    }

    @Test
    fun `paged conversations factory injects existing iroh source`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = Json.parseToJsonElement(
                        Json.encodeToString(listOf(TestData.conversation(id = "iroh-page"))),
                    ),
                )
            }
        }
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        val pagedRepository = AllConversationsRepository(
            conversationApi = armedConversationApi(),
            conversationDao = null,
            repositoryScope = this,
            settingsRepository = settings,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
        )

        val pagingSource = pagedRepository.createConversationsPagingSource(null, null, null)
        assertTrue(pagingSource.pageLoader != null)
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page

        assertEquals(listOf("iroh-page"), result.data.map { it.id.value })
        assertEquals("conversation.list", transport.adminRpcCalls.single().method)
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
        assertEquals(listOf("new"), repository.conversations.value.map { it.id.value })
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

        assertEquals(listOf("cached"), repository.conversations.value.map { it.id.value })
    }

    @Test
    fun `handleOptimisticUpdate adds new conversation`() {
        val conv = TestData.conversation(id = "new-1")
        repository.handleOptimisticUpdate(conv)
        assertEquals(1, repository.conversations.value.size)
        assertEquals("new-1", repository.conversations.value.first().id.value)
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
        repository.handleOptimisticDelete(com.letta.mobile.data.model.ConversationId("1"))
        assertEquals(1, repository.conversations.value.size)
        assertTrue(repository.conversations.value.none { it.id.value == "1" })
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

    // M6 (data-efficiency-audit): refresh() and loadNextPage() must be
    // serialized by the same mutex, otherwise concurrent calls can race on
    // `currentCursor` / `_conversations` / `_hasMore`.
    @Test
    fun `concurrent refresh and loadNextPage are serialized by mutex`() = runTest {
        // Seed enough conversations so refresh's first page AND loadNextPage's
        // second page both have work to do. PAGE_SIZE is 50.
        val total = 120
        repeat(total) { index ->
            fakeApi.conversations.add(TestData.conversation(id = "conv-$index"))
        }
        fakeApi.listDelayMillis = 5L
        fakeApi.calls.clear()

        val refreshJob = launch { repository.refresh() }
        val loadJob = launch { repository.loadNextPage() }
        joinAll(refreshJob, loadJob)

        val listCalls = fakeApi.calls.count { it == "listConversations" }
        // Each distinct cursor position can issue at most one listConversations.
        // With the mutex, refresh's after=null and loadNextPage's after=<last-of-page-1>
        // can't collide; pre-fix this could fire 3+ due to a race on the cursor.
        assertTrue(
            "expected \u2264 2 listConversations calls, got $listCalls (${fakeApi.calls})",
            listCalls <= 2,
        )
        // The mutex serializes fetchPage + refresh: the peak number of
        // concurrent listConversations() in-flight at any moment must be 1.
        // Without the mutex, refresh and loadNextPage would both call
        // listConversations simultaneously and the peak would be >= 2.
        assertEquals(
            "mutex must serialize; observed peak concurrent listConversations calls=${fakeApi.peakConcurrentListConversations.get()}",
            1,
            fakeApi.peakConcurrentListConversations.get(),
        )
        // No duplicates: each conversation id is present at most once.
        val ids = repository.conversations.value.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size)
        // The union of all loaded pages must equal the conversations set in
        // order — we got at least one page back.
        assertTrue(ids.size >= 50)
    }

    private class FakeLocalRuntimeConversationSource(
        private val conversationsProvider: suspend () -> List<Conversation>,
    ) : LocalRuntimeConversationSource {
        override suspend fun listConversations(): List<Conversation> = conversationsProvider()

        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation =
            throw AssertionError("local create must not be used by all-conversations refresh")
    }
}
