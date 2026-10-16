package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeAgentRepository
import com.letta.mobile.testutil.TestData
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.ConversationCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.letta.mobile.data.chat.recompile.IrohConversationRecompileRpc
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because the
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ConversationRepositoryTest {

    private lateinit var fakeApi: FakeConversationApi
    private lateinit var repository: ConversationRepository
    private lateinit var database: LettaDatabase
    private lateinit var repositoryScope: CoroutineScope

    @Before
    fun setup() {
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApi = FakeConversationApi()
        val agentRepository = FakeAgentRepository()
        repository = ConversationRepository(
            fakeApi, agentRepository, lazyOf(database.conversationDao()), repositoryScope = repositoryScope,
        )
    }

    @After
    fun tearDown() {
        repositoryScope.cancel()
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {}

        val result = repository.getConversations("a1").first()
        assertEquals("Original", result.first().summary)
    }

    @Test
    fun `updateConversation in iroh mode bypasses HTTP and stores RPC result`() = runTest {
        val settings = irohSettings()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, path, body ->
                assertEquals("conversation.update", method)
                assertEquals("/v1/conversations/1", path)
                assertEquals("""{"summary":"Updated"}""", body)
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = Json.parseToJsonElement(
                        """{"id":"1","agent_id":"a1","summary":"Server summary"}""",
                    ),
                )
            }
        }
        val httpThatThrows = object : FakeConversationApi() {
            override suspend fun updateConversation(
                conversationId: com.letta.mobile.data.model.ConversationId,
                params: com.letta.mobile.data.model.ConversationUpdateParams,
            ): Conversation = throw AppServerRouteUnavailable(SessionRoute.CONVERSATIONS, "iroh://test-node")
        }
        httpThatThrows.conversations.add(TestData.conversation(id = "1", agentId = "a1", summary = "Original"))
        repository = ConversationRepository(
            httpThatThrows,
            FakeAgentRepository(),
            lazyOf(database.conversationDao()),
            repositoryScope = repositoryScope,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
        )
        database.conversationDao().upsert(
            ConversationEntity.fromConversation(httpThatThrows.conversations.single(), cachedAtEpochMs = 10L),
        )

        repository.updateConversation("1", "a1", "Updated")

        assertEquals("Server summary", repository.getConversations("a1").first().single().summary)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `updateConversation rolls back when iroh RPC fails`() = runTest {
        val settings = irohSettings()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse("req", success = false, error = "RPC failed")
            }
        }
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1", summary = "Original"))
        repository = ConversationRepository(
            fakeApi,
            FakeAgentRepository(),
            lazyOf(database.conversationDao()),
            repositoryScope = repositoryScope,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
        )
        database.conversationDao().upsert(
            ConversationEntity.fromConversation(fakeApi.conversations.single(), cachedAtEpochMs = 10L),
        )

        val thrown = runCatching { repository.updateConversation("1", "a1", "Updated") }.exceptionOrNull()

        assertTrue(thrown!!.message.orEmpty().contains("RPC failed"))
        assertEquals("Original", repository.getConversations("a1").first().single().summary)
        assertTrue(fakeApi.calls.none { it.startsWith("updateConversation") })
    }

    private fun irohSettings() = FakeSettingsRepository(
        initialActiveConfig = LettaConfig(
            id = "iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://test-node",
        ),
    )

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

    // The all-conversations screen archives rows whose agent list was never opened; the write must
    // still reach the server, or the row only looks archived until the next refresh.
    @Test
    fun `setConversationArchived reaches the server when the agent list is not cached`() = runTest {
        fakeApi.conversations.add(TestData.conversation(id = "1", agentId = "a1", summary = "Old").copy(archived = false))

        repository.setConversationArchived("1", "a1", true)

        assertTrue(fakeApi.calls.any { it.startsWith("updateConversation") })
    }

    // letta-mobile-xj85j: a conversation another device creates lands in a loaded agent list.
    @Test
    fun `conversation_updated push upserts the conversation into a loaded agent list`() = runTest {
        val settings = irohSettings()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, path, _ ->
                assertEquals("conversation.get", method)
                assertEquals("/v1/conversations/from-phone", path)
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = Json.parseToJsonElement("""{"id":"from-phone","agent_id":"a1","summary":"From phone"}"""),
                )
            }
        }
        database.conversationDao().upsert(
            ConversationEntity.fromConversation(TestData.conversation(id = "1", agentId = "a1"), cachedAtEpochMs = 10L),
        )
        val pushed = ConversationRepository(
            fakeApi,
            FakeAgentRepository(),
            lazyOf(database.conversationDao()),
            repositoryScope = backgroundScope,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
            transport = transport,
        )
        runCurrent()

        transport.events.emit(conversationPush("from-phone", agentId = "a1"))

        val listed = pushed.getConversations("a1").first { rows -> rows.any { it.id.value == "from-phone" } }
        assertEquals(setOf("1", "from-phone"), listed.map { it.id.value }.toSet())
    }

    @Test
    fun `conversation_updated refetch failure leaves loaded list unchanged`() = runTest {
        val settings = irohSettings()
        // applyPush gates the refetch on a Room query that runs on Room's own executor, outside the
        // test scheduler, so runCurrent() can return before the refetch happens. Await the refetch.
        val refetched = CompletableDeferred<Unit>()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                refetched.complete(Unit)
                AppServerInboundFrame.AdminRpcResponse("req", success = false, error = "RPC failed")
            }
        }
        val cached = TestData.conversation(id = "existing", agentId = "a1")
        database.conversationDao().upsert(ConversationEntity.fromConversation(cached, cachedAtEpochMs = 10L))
        val pushed = ConversationRepository(
            fakeApi,
            FakeAgentRepository(),
            lazyOf(database.conversationDao()),
            repositoryScope = backgroundScope,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
            transport = transport,
        )
        runCurrent()

        transport.events.emit(conversationPush("missing", agentId = "a1"))
        refetched.await()

        assertEquals(1, transport.adminRpcCalls.size)
        assertEquals(listOf("existing"), pushed.getConversations("a1").first().map { it.id.value })
    }

    @Test
    fun `conversation_updated push for an agent never loaded here fetches nothing`() = runTest {
        val settings = irohSettings()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ -> fail("unexpected $method"); error("unreachable") }
        }
        ConversationRepository(
            fakeApi,
            FakeAgentRepository(),
            lazyOf(database.conversationDao()),
            repositoryScope = backgroundScope,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
            transport = transport,
        )
        runCurrent()

        transport.events.emit(conversationPush("elsewhere", agentId = "a-unloaded"))
        runCurrent()

        assertTrue(transport.adminRpcCalls.isEmpty())
    }

    private fun conversationPush(conversationId: String, agentId: String?) = ServerFrame.ConversationUpdated(
        id = "conversation-updated-$conversationId",
        ts = "2026-09-25T21:00:00Z",
        conversationId = conversationId,
        agentId = agentId,
        reason = "created",
    )

    @Test
    fun `cancelConversation delegates to api`() = runTest {
        repository.cancelConversation("1", "a1")
        assertTrue(fakeApi.calls.contains("cancelConversation:1"))
    }

    @Test
    fun `no route offers neither cancel nor recompile off Iroh`() {
        assertFalse(repository.canCancelConversationRuns())
        assertFalse(repository.canRecompileConversations())
    }

    @Test
    fun `cancelConversation over Iroh aborts through the channel transport, not the remote seam`() = runTest {
        val transport = FakeChannelTransport()
        repository = irohRepository(transport)

        assertTrue(repository.canCancelConversationRuns())
        assertFalse(repository.canRecompileConversations())
        repository.cancelConversation("conv-1", "a1")

        assertEquals(listOf("conv-1"), transport.cancelCalls)
        assertTrue(transport.adminRpcCalls.isEmpty())
        assertFalse(fakeApi.calls.any { it.startsWith("cancelConversation") })
    }

    @Test
    fun `cancelConversation over Iroh with no run on this connection says so`() = runTest {
        val transport = FakeChannelTransport().apply { cancelResult = false }
        repository = irohRepository(transport)

        val error = runCatching { repository.cancelConversation("conv-1", "a1") }.exceptionOrNull()

        assertTrue(error is NoActiveConversationRunException)
        assertEquals("No run of conversation conv-1 is active on this connection.", error?.message)
        assertEquals(listOf("conv-1"), transport.cancelCalls)
    }

    private fun irohRepository(transport: FakeChannelTransport): ConversationRepository {
        val settings = irohSettings()
        return ConversationRepository(
            fakeApi,
            FakeAgentRepository(),
            lazyOf(database.conversationDao()),
            repositoryScope = repositoryScope,
            irohConversationListSource = IrohAdminRpcConversationListSource(transport, settings),
            transport = transport,
        )
    }

    @Test
    fun `recompile over Iroh goes through the host's conversation recompile relay`() = runTest {
        val transport = FakeChannelTransport().apply {
            advertisedCapabilities = setOf(IrohConversationRecompileRpc.CAPABILITY)
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "r",
                    success = true,
                    result = IrohConversationRecompileRpc.result("compiled prompt", dryRun = false),
                )
            }
        }
        repository = irohRepository(transport)

        assertTrue(repository.canRecompileConversations())
        val prompt = repository.recompileConversation(ConversationId("conv-1"), false, AgentId("a1"))

        assertEquals("compiled prompt", prompt)
        val call = transport.adminRpcCalls.single()
        assertEquals(IrohConversationRecompileRpc.METHOD, call.method)
        val params = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("conv-1", params.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("a1", params.getValue("agent_id").jsonPrimitive.content)
        assertFalse("dry_run" in params)
        assertFalse(fakeApi.calls.any { it.startsWith("recompileConversation") })
    }

    @Test
    fun `recompile over Iroh surfaces the host's refusal`() = runTest {
        val transport = FakeChannelTransport().apply {
            advertisedCapabilities = setOf(IrohConversationRecompileRpc.CAPABILITY)
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = false, error = "recompile_failed: nope")
            }
        }
        repository = irohRepository(transport)

        val error = runCatching { repository.recompileConversation(ConversationId("conv-1"), true, AgentId("a1")) }.exceptionOrNull()

        assertEquals("recompile_failed: nope", error?.message)
        val params = Json.parseToJsonElement(transport.adminRpcCalls.single().body!!).jsonObject
        assertEquals("true", params.getValue("dry_run").jsonPrimitive.content)
    }

    @Test
    fun `an Iroh host without the recompile relay offers no recompile and is never asked`() = runTest {
        val transport = FakeChannelTransport()
        repository = irohRepository(transport)

        assertFalse(repository.canRecompileConversations())
        val error = runCatching { repository.recompileConversation(ConversationId("conv-1"), false, AgentId("a1")) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertTrue(transport.adminRpcCalls.isEmpty())
        assertFalse(fakeApi.calls.any { it.startsWith("recompileConversation") })
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
    // letta-mobile-by4vm.16: a refresh that lands between an optimistic delete and its failed
    // rollback survives; the rollback re-inserts only the deleted row instead of a stale snapshot.
    @Test
    fun failedDeleteRestoresOnlyTheDeletedRowOverAConcurrentRefresh() = runTest {
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val gatedApi = object : FakeConversationApi() {
            override suspend fun deleteConversation(conversationId: ConversationId) {
                deleteEntered.complete(Unit)
                releaseDelete.await()
                throw ApiException(500, "Server error")
            }
        }
        gatedApi.conversations.addAll(listOf(conversation("1"), conversation("2")))
        repository = ConversationRepository(
            gatedApi, FakeAgentRepository(), lazyOf(database.conversationDao()), repositoryScope = repositoryScope,
        )
        repository.refreshConversations("a1")

        val delete = launch {
            try {
                repository.deleteConversation("1", "a1")
                fail("Expected the delete to fail")
            } catch (_: ApiException) {
                // Expected: the server rejected the delete.
            }
        }
        deleteEntered.await()
        gatedApi.conversations = mutableListOf(conversation("2"), conversation("3"))
        repository.refreshConversations("a1")
        releaseDelete.complete(Unit)
        delete.join()

        val expected = setOf("1", "2", "3")
        assertEquals(expected, repository.getCachedConversations(AgentId("a1")).map { it.id.value }.toSet())
        assertEquals(expected, repository.getConversations("a1").first().map { it.id.value }.toSet())
    }

    @Test
    fun optimisticRenameDoesNotSuppressTheNextStaleRefresh() = runTest {
        database.conversationDao().upsert(ConversationEntity.fromConversation(conversation("1"), cachedAtEpochMs = 10L))
        database.conversationDao().upsertRefreshState(ConversationRefreshEntity(agentId = "a1", lastRefreshAtMillis = 10L))
        fakeApi.conversations.add(conversation("1"))
        val initScope = childScope()
        repository = ConversationRepository(
            fakeApi, FakeAgentRepository(), lazyOf(database.conversationDao()), repositoryScope = initScope,
        )
        initScope.coroutineContext.job.children.toList().joinAll()

        repository.updateConversation("1", "a1", "Renamed")

        assertFalse(repository.hasFreshConversations(AgentId("a1"), maxAgeMs = 60_000))
        assertTrue(repository.refreshConversationsIfStale("a1", maxAgeMs = 60_000))
        assertEquals(1, fakeApi.calls.count { it == "listConversations" })
    }

    @Test
    fun initialCacheLoadDoesNotClobberARefreshThatFinishedFirst() = runTest {
        val realDao = database.conversationDao()
        realDao.upsert(ConversationEntity.fromConversation(conversation("stale"), cachedAtEpochMs = 10L))
        val initRead = CompletableDeferred<Unit>()
        val releaseInit = CompletableDeferred<Unit>()
        val gatedDao = object : ConversationDao by realDao {
            override suspend fun getAllOnce(): List<ConversationEntity> = realDao.getAllOnce().also {
                initRead.complete(Unit)
                releaseInit.await()
            }
        }
        val initScope = childScope()
        repository = ConversationRepository(
            fakeApi, FakeAgentRepository(), lazyOf<ConversationDao>(gatedDao), repositoryScope = initScope,
        )
        initRead.await()
        fakeApi.conversations.add(conversation("fresh"))
        repository.refreshConversations("a1")
        releaseInit.complete(Unit)
        initScope.coroutineContext.job.children.toList().joinAll()

        assertEquals(listOf("fresh"), repository.getCachedConversations(AgentId("a1")).map { it.id.value })
        assertTrue(repository.hasFreshConversations(AgentId("a1"), maxAgeMs = 60_000))
    }

    @Test
    fun concurrentCreatesAndRefreshesOnDefaultDispatcherKeepEveryAgentFresh() = runTest {
        val threadSafeApi = object : FakeConversationApi() {
            override suspend fun createConversation(params: ConversationCreateParams): Conversation =
                TestData.conversation(id = "new-${params.agentId.value}", agentId = params.agentId.value)

            override suspend fun listConversations(
                agentId: AgentId?,
                limit: Int?,
                after: String?,
                archiveStatus: String?,
                summarySearch: String?,
                order: String?,
                orderBy: String?,
            ): List<Conversation> = emptyList()
        }
        repository = ConversationRepository(
            threadSafeApi, FakeAgentRepository(), lazyOf(database.conversationDao()), repositoryScope = repositoryScope,
        )
        val agents = List(16) { AgentId("agent-$it") }

        withContext(Dispatchers.Default) {
            agents.flatMap { agent ->
                listOf(
                    launch { repository.createConversation(agent, "created") },
                    launch { repository.refreshConversations(agent) },
                    launch { repeat(50) { repository.hasFreshConversations(agent, maxAgeMs = 60_000) } },
                )
            }.joinAll()
        }

        assertTrue(agents.all { repository.hasFreshConversations(it, maxAgeMs = 60_000) })
    }

    private fun conversation(id: String) = TestData.conversation(id = id, agentId = "a1")

    /** A scope torn down with [repositoryScope] whose init job a test can join. */
    private fun childScope() = CoroutineScope(repositoryScope.coroutineContext + Job(repositoryScope.coroutineContext.job))

}
