package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.testutil.FakeMessageApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MessagePagingSourceTest {

    private lateinit var messageApi: FakeMessageApi
    private lateinit var pagingSource: MessagePagingSource

    @Before
    fun setup() {
        messageApi = FakeMessageApi()
        pagingSource = MessagePagingSource(
            messageApi = messageApi,
            agentId = AgentId("agent-123"),
            conversationId = ConversationId("conv-456")
        )
    }

    @Test
    fun `loads first page with messages`() = runTest {
        messageApi.messages.addAll(
            listOf(
                UserMessage(id = "msg-1", contentRaw = JsonPrimitive("hello")),
                UserMessage(id = "msg-2", contentRaw = JsonPrimitive("again")),
            )
        )

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false
            )
        )

        result as PagingSource.LoadResult.Page
        assertEquals(2, result.data.size)
    }

    @Test
    fun `returns null next key when no more messages`() = runTest {
        messageApi.messages.add(UserMessage(id = "msg-1", contentRaw = JsonPrimitive("hello")))

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false
            )
        )

        result as PagingSource.LoadResult.Page
        assertEquals(1, result.data.size)
        assertNull(result.nextKey)
    }

    @Test
    fun `returns error on API failure`() = runTest {
        messageApi.shouldFail = true

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false
            )
        )

        assert(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `getRefreshKey returns null`() {
        val state = PagingState<String, AppMessage>(
            pages = emptyList(),
            anchorPosition = null,
            config = androidx.paging.PagingConfig(50),
            leadingPlaceholderCount = 0,
        )

        val refreshKey = pagingSource.getRefreshKey(state)
        assertNull(refreshKey)
    }
}
