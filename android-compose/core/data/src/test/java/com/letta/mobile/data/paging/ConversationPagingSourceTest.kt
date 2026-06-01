package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConversationPagingSourceTest {

    private lateinit var conversationApi: FakeConversationApi
    private lateinit var pagingSource: ConversationPagingSource

    @Before
    fun setup() {
        conversationApi = FakeConversationApi()
        pagingSource = ConversationPagingSource(conversationApi = conversationApi)
    }

    @Test
    fun `loads first page with cursor limit`() = runTest {
        repeat(75) { index ->
            conversationApi.conversations.add(TestData.conversation(id = "conv-$index"))
        }

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false,
            ),
        )

        result as PagingSource.LoadResult.Page
        assertEquals(50, result.data.size)
        assertEquals("conv-49", result.nextKey)
        assertEquals(listOf(50), conversationApi.listLimits)
    }

    @Test
    fun `loads append page after cursor`() = runTest {
        repeat(75) { index ->
            conversationApi.conversations.add(TestData.conversation(id = "conv-$index"))
        }

        val result = pagingSource.load(
            PagingSource.LoadParams.Append(
                key = "conv-49",
                loadSize = 50,
                placeholdersEnabled = false,
            ),
        )

        result as PagingSource.LoadResult.Page
        assertEquals((50 until 75).map { "conv-$it" }, result.data.map { it.id.value })
        assertNull(result.nextKey)
        assertEquals(listOf(50), conversationApi.listLimits)
    }

    @Test
    fun `passes filters to api`() = runTest {
        pagingSource = ConversationPagingSource(
            conversationApi = conversationApi,
            agentId = AgentId("agent-1"),
            archiveStatus = "unarchived",
            summarySearch = "important",
            order = "desc",
            orderBy = "last_message_at",
        )
        conversationApi.conversations.addAll(
            listOf(
                TestData.conversation(id = "match", agentId = "agent-1", summary = "important thread"),
                TestData.conversation(id = "other-agent", agentId = "agent-2", summary = "important thread"),
                TestData.conversation(id = "archived", agentId = "agent-1", summary = "important archived").copy(archived = true),
            ),
        )

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            ),
        )

        result as PagingSource.LoadResult.Page
        assertEquals(listOf("match"), result.data.map { it.id.value })
        assertEquals(listOf(20), conversationApi.listLimits)
    }

    @Test
    fun `returns error on api failure`() = runTest {
        conversationApi.shouldFail = true

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false,
            ),
        )

        assert(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `getRefreshKey returns null`() {
        val state = PagingState<String, Conversation>(
            pages = emptyList(),
            anchorPosition = null,
            config = androidx.paging.PagingConfig(50),
            leadingPlaceholderCount = 0,
        )

        assertNull(pagingSource.getRefreshKey(state))
    }
}
