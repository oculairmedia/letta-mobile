package com.letta.mobile.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.IrohAdminRpcConversationListSource
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import com.letta.mobile.testutil.armedConversationApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `iroh paging forwards filters limit and cursor without using http`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> irohSuccess(listOf(TestData.conversation(id = "iroh-1", agentId = "agent-1"))) }
        }
        val source = ConversationPagingSource(
            conversationApi = armedConversationApi(),
            pageLoader = irohPageLoader(transport),
            agentId = AgentId("agent-1"),
            archiveStatus = "unarchived",
            summarySearch = "important",
            order = "desc",
            orderBy = "last_message_at",
        )

        val result = source.load(refresh(loadSize = 20))

        if (result is PagingSource.LoadResult.Error) throw result.throwable
        result as PagingSource.LoadResult.Page
        assertEquals(listOf("iroh-1"), result.data.map { it.id.value })
        assertNull(result.nextKey)
        val call = transport.adminRpcCalls.single()
        assertEquals("conversation.list", call.method)
        assertEquals("/v1/conversations", call.path)
        assertEquals(
            """{"agent_id":"agent-1","limit":"20","archive_status":"unarchived","summary_search":"important","order":"desc","order_by":"last_message_at"}""",
            call.body,
        )
        assertFalse(call.body.orEmpty().contains("\"after\""))
    }

    @Test
    fun `iroh paging advances each full page cursor once then stops on short page`() = runTest {
        val pages = ArrayDeque(
            listOf(
                page("first", 2),
                page("second", 2),
                page("last", 1),
            ),
        )
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> irohSuccess(pages.removeFirst()) }
        }
        val source = ConversationPagingSource(
            conversationApi = armedConversationApi(),
            pageLoader = irohPageLoader(transport),
        )

        val first = source.load(refresh(loadSize = 2)) as PagingSource.LoadResult.Page
        val second = source.load(append(first.nextKey!!, loadSize = 2)) as PagingSource.LoadResult.Page
        val third = source.load(append(second.nextKey!!, loadSize = 2)) as PagingSource.LoadResult.Page

        assertEquals("first-1", first.nextKey)
        assertEquals("second-1", second.nextKey)
        assertNull(third.nextKey)
        assertEquals(
            listOf(null, "first-1", "second-1"),
            transport.adminRpcCalls.map { call ->
                Json.parseToJsonElement(call.body!!).jsonObject["after"]?.jsonPrimitive?.content
            },
        )
    }

    @Test
    fun `iroh empty page is terminal while failure and transport throw are errors`() = runTest {
        val emptyTransport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> irohSuccess(emptyList()) }
        }
        val emptyResult = ConversationPagingSource(
            conversationApi = armedConversationApi(),
            pageLoader = irohPageLoader(emptyTransport),
        ).load(refresh(loadSize = 50))
        emptyResult as PagingSource.LoadResult.Page
        assertTrue(emptyResult.data.isEmpty())
        assertNull(emptyResult.nextKey)

        val failureTransport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse("req", success = false, error = "paging failed")
            }
        }
        val failureResult = ConversationPagingSource(
            conversationApi = armedConversationApi(),
            pageLoader = irohPageLoader(failureTransport),
        ).load(refresh(loadSize = 50))
        assertTrue(failureResult is PagingSource.LoadResult.Error)

        val throwingTransport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> throw IllegalStateException("transport failed") }
        }
        val throwResult = ConversationPagingSource(
            conversationApi = armedConversationApi(),
            pageLoader = irohPageLoader(throwingTransport),
        ).load(refresh(loadSize = 50))
        assertTrue(throwResult is PagingSource.LoadResult.Error)
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

    private fun irohPageLoader(transport: FakeChannelTransport): ConversationPageLoader {
        val source = IrohAdminRpcConversationListSource(
            transport,
            FakeSettingsRepository(
                initialActiveConfig = LettaConfig(
                    id = "iroh",
                    mode = LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = "iroh://EndpointTicket",
                ),
            ),
        )
        return { agentId, limit, after, archiveStatus, summarySearch, order, orderBy ->
            source.listConversations(agentId, limit, after, archiveStatus, summarySearch, order, orderBy)
        }
    }

    private fun irohSuccess(conversations: List<Conversation>) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req",
        success = true,
        result = Json.parseToJsonElement(Json.encodeToString(conversations)),
    )

    private fun page(prefix: String, size: Int): List<Conversation> =
        List(size) { index -> TestData.conversation(id = "$prefix-$index") }

    private fun refresh(loadSize: Int) = PagingSource.LoadParams.Refresh<String>(
        key = null,
        loadSize = loadSize,
        placeholdersEnabled = false,
    )

    private fun append(key: String, loadSize: Int) = PagingSource.LoadParams.Append(
        key = key,
        loadSize = loadSize,
        placeholdersEnabled = false,
    )

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
