package com.letta.mobile.web.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.conversations.ConversationManagementPrefs
import com.letta.mobile.data.chat.conversations.TimelineConversations
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.home.HomePageController
import com.letta.mobile.data.home.HomePageSource
import com.letta.mobile.data.home.HomePinStore
import com.letta.mobile.data.home.HomeSearchCatalog
import com.letta.mobile.data.home.HomeStats
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.ConversationId as DataConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.IrohApprovalControls
import com.letta.mobile.data.runtime.PermissionModeRpc
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.ui.chat.session.timelineModelSource
import com.letta.mobile.ui.shell.conversations.rememberShellTimelineConversationList
import com.letta.mobile.ui.shell.conversations.rememberTimelineConversations
import com.letta.mobile.ui.shell.pages.ShellHomeBackendFleetEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WasmAppServerSessionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `websocket conversation push lists only its agent`() = runTest {
        val otherAgent = AgentId("agent-0000000000000000000000000000000000000002")
        val otherConversation = DataConversationId("conv-0000000000000000000000000000000000000003")
        val transport = ConversationListTransport(JsonArray(listOf(
            conversationRow(CONVERSATION_ID_1, AGENT_ID, Instant.parse("2026-10-20T10:00:00Z")),
            conversationRow(otherConversation, otherAgent, Instant.parse("2026-10-21T10:00:00Z")),
        )))
        val owner = TimelineConversations(
            backgroundScope, backgroundScope.chatGateway(transport),
            listOf(Agent(AGENT_ID, name = "Nora"), Agent(otherAgent, name = "Ivo")),
        )
        runCurrent()
        val fullFleetBytes = transport.requestBytes.sum()
        owner.selectConversation(otherConversation)
        owner.stageComposerText("keep draft")
        val drafts = owner.state.value.drafts
        transport.requests.clear()
        transport.requestBytes.clear()

        transport.pushConversation(CONVERSATION_ID_1, AGENT_ID)
        runCurrent()

        assertEquals(listOf(AGENT_ID.value), transport.requests.map { it.query?.get("agent_id")?.jsonPrimitive?.content })
        val scopedBytes = transport.requestBytes.sum()
        assertTrue(scopedBytes in 1 until fullFleetBytes)
        println("websocket N=2 full-fleet requests=2 bytes=$fullFleetBytes scoped requests=1 bytes=$scopedBytes")
        assertEquals(otherConversation, owner.selectedConversation)
        assertEquals(drafts, owner.state.value.drafts)
    }

    @Test
    fun `transport disconnect unblocks router subscribers`() = runTest {
        val transport = FakeTransport()
        val client = DefaultAppServerClient(transport)
        val router = AppServerRuntimeEventRouter()
        val session = WasmAppServerSession(
            link = WasmAppServerLink(client, transport.isConnected, failureReason = { null }, label = "test", close = {}),
            services = WasmAppServerServices(
                engine = AppServerTurnEngine(client = client, eventRouter = router),
                router = router,
                appServerAdmin = AppServerLocalAdminGateway(client) { operation -> "test-$operation" },
            ),
        )
        val (_, events) = router.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        val started = CompletableDeferred<Unit>()
        val completion = async {
            try {
                events.onStart { started.complete(Unit) }.toList()
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
        }
        started.await()

        session.onTransportDisconnected()

        assertIs<CancellationException>(withTimeout(100) { completion.await() })
        assertTrue(completion.isCompleted)
    }

    @Test
    fun `http endpoints resolve to the one socket app server path`() {
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("http://localhost:4500/"))
        assertEquals("wss://api.example.com/ws", resolveWebSocketUrl("https://api.example.com"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500/ws"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500"))
    }

    @Test
    fun `iroh endpoint never falls back to websocket`() {
        assertFailsWith<IllegalStateException> { resolveWebSocketUrl("iroh://0123456789abcdef") }
    }

    @Test
    fun `iroh auth asks the server to split replies larger than one frame`() {
        assertEquals(
            listOf("frame_part"),
            authCapabilitiesFor("iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f"),
        )
        assertNull(authCapabilitiesFor("http://localhost:4500"))
        assertNull(authCapabilitiesFor("wss://api.example.com/ws"))
    }

    @Test
    fun `request ids do not collide after a page reload`() {
        assertNotEquals(webRequestId("page-a", "message", 9), webRequestId("page-b", "message", 9))
        assertEquals("web-page-b-message-9", webRequestId("page-b", "message", 9))
    }

    @Test
    fun `over iroh the chat lists and switches models through the shared gateway's admin rpc`() = runTest {
        val host = RecordingHost()
        val source = timelineModelSource(IrohAdminRpcChatGateway(host), nativeAdmin(), null, unexpectedModelControl())

        source.backend.listModels()
        source.backend.switchModel(
            ConversationModelChange(DataConversationId("conv-1"), ModelHandle("openai/gpt-sol"), ReasoningEffortChoice.Named("high")),
        )

        assertTrue(source.backend.supportsReasoningEffort, "the effort chip shows over an Iroh host")
        assertTrue("model.list" in host.methods && "model.update" in host.methods)
        assertEquals("openai/gpt-sol", source.conversation(DataConversationId("conv-1")).model)
    }

    @Test
    fun `a websocket chat keeps the app server's own model commands`() = runTest {
        val source = timelineModelSource(null, nativeAdmin(), null, unexpectedModelControl())

        assertFalse(source.backend.supportsReasoningEffort)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `wasm conversation owner loads late roster through the app server wire shape and recovers`() = runTest {
        val roster = listOf(Agent(id = AGENT_ID, name = "Nora"))
        val rows = JsonArray(
            listOf(
                conversationRow(CONVERSATION_ID_1, AGENT_ID, Instant.parse("2026-10-20T10:00:00Z")),
                conversationRow(CONVERSATION_ID_2, AGENT_ID, Instant.parse("2026-10-21T10:00:00Z")),
            ),
        )
        val transport = ConversationListTransport(rows, failLists = true)
        val owner = TimelineConversations(
            backgroundScope,
            backgroundScope.chatGateway(transport),
            emptyList(),
            ConversationManagementPrefs(),
        )
        runCurrent()

        assertTrue(transport.requests.isEmpty(), "the connected owner waits while its roster is empty")
        assertEquals(false, owner.state.value.loaded)

        owner.roster = roster
        runCurrent()

        assertTrue(!owner.state.value.error.isNullOrBlank(), "the failed App Server list remains visible in owner state")
        assertTrue(owner.state.value.conversations.isEmpty())
        val query = assertNotNull(transport.requests.single().query)
        assertEquals(JsonPrimitive(AGENT_ID.value), query["agent_id"])
        assertEquals(JsonPrimitive(50), query["limit"])
        assertEquals(JsonPrimitive("all"), query["archive_status"])
        assertEquals(JsonPrimitive("desc"), query["order"])
        assertEquals(JsonPrimitive("last_message_at"), query["order_by"])

        transport.failLists = false
        owner.refresh()
        owner.refresh()

        assertNull(owner.state.value.error)
        assertEquals(false, owner.state.value.conversations.any { it.agentId != AGENT_ID })
        assertEquals(
            setOf(CONVERSATION_ID_1.value, CONVERSATION_ID_2.value),
            owner.state.value.conversations.map { it.id.value }.toSet(),
        )
        assertEquals(2, owner.state.value.conversations.size)

        val reconnected = TimelineConversations(
            backgroundScope,
            backgroundScope.chatGateway(ConversationListTransport(rows)),
            roster,
            ConversationManagementPrefs(),
        )
        runCurrent()

        assertEquals(
            setOf(CONVERSATION_ID_1.value, CONVERSATION_ID_2.value),
            reconnected.state.value.conversations.map { it.id.value }.toSet(),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `web home receives conversations when its composed owner gets a late roster`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = ConversationListTransport(
            JsonArray(
                listOf(
                    conversationRow(CONVERSATION_ID_1, AGENT_ID, Instant.parse("2026-10-20T10:00:00Z")),
                    conversationRow(CONVERSATION_ID_2, AGENT_ID, Instant.parse("2026-10-21T10:00:00Z")),
                ),
            ),
        )
        val gateway = scope.chatGateway(transport)
        val home = HomePageController(EmptyHomePageSource, EmptyHomePinStore, scope)
        var roster by mutableStateOf(emptyList<Agent>())
        var owner: TimelineConversations? = null

        try {
            setContent {
                val liveOwner = rememberTimelineConversations(
                    connection = BackendConnectionStatus.Connected("App Server"),
                    backend = { gateway },
                    agents = roster,
                    prefs = ConversationManagementPrefs(),
                )
                owner = liveOwner
                val list = rememberShellTimelineConversationList(liveOwner)
                ShellHomeBackendFleetEffect(home, list.state.conversations, roster)
            }

            waitUntil("the connected timeline owner is produced") { owner != null }
            val retainedOwner = assertNotNull(owner)
            assertFalse(retainedOwner.state.value.loaded)
            assertTrue(transport.requests.isEmpty(), "the empty initial roster does not query the App Server")
            assertTrue(home.state.value.fleet.recent.isEmpty())

            roster = listOf(Agent(id = AGENT_ID, name = "Nora"))

            waitUntil("the same owner loads conversations after the roster arrives", timeoutMillis = 5_000) {
                retainedOwner.state.value.loaded
            }
            assertEquals(
                setOf(CONVERSATION_ID_1.value, CONVERSATION_ID_2.value),
                retainedOwner.state.value.conversations.map { it.id.value }.toSet(),
            )
            assertEquals(1, transport.requests.size)

            waitUntil("Home's fleet receives the owner's delayed conversation list", timeoutMillis = 5_000) {
                home.state.value.fleet.recent.map { it.conversationId }.toSet() ==
                    setOf(CONVERSATION_ID_1.value, CONVERSATION_ID_2.value)
            }

            assertSame(retainedOwner, owner, "the roster update reaches the same owner produced at connection time")
            assertEquals(setOf("Nora"), home.state.value.fleet.recent.map { it.agentName }.toSet())
            assertEquals(2, home.state.value.fleet.summary.totalConversations)
        } finally {
            home.close()
            scope.cancel()
        }
    }

    @Test
    fun `over iroh the mode chip is the host's, and a closed session lets the gateway go`() = runTest {
        val transport = FakeTransport()
        val client = DefaultAppServerClient(transport)
        val router = AppServerRuntimeEventRouter()
        val session = WasmAppServerSession(
            link = WasmAppServerLink(client, transport.isConnected, failureReason = { null }, label = "test", close = {}),
            services = WasmAppServerServices(
                engine = AppServerTurnEngine(client = client, eventRouter = router),
                router = router,
                appServerAdmin = AppServerLocalAdminGateway(client) { operation -> "test-$operation" },
            ),
        )
        assertNull(session.permissionModes, "no mode chip until the host's gateway is bound")

        val host = RecordingHost(setOf(PermissionModeRpc.CAPABILITY))
        val gateway = IrohAdminRpcChatGateway(
            host,
            approvalControls = IrohApprovalControls(backgroundScope, host, PermissionModeSettings(InMemorySecureSettingsStore()).modes),
        )
        session.bindIrohChat(gateway)

        assertSame(gateway, session.permissionModes)
        assertSame(gateway, session.conversations, "the conversation owner lists and edits through the same gateway")
        assertNotNull(gateway.permissionModes, "the host advertises the mode relay")
        session.close()
        assertNull(session.irohChat)
        assertNull(session.conversations)
    }

    private fun nativeAdmin() = AppServerLocalAdminGateway(DefaultAppServerClient(FakeTransport())) { operation -> "test-$operation" }

    private val unexpectedRpc = AdminRpcInvoker { method, _ -> error("unexpected $method") }

    private fun unexpectedModelControl() = ModelControlSession(unexpectedRpc, oauthHttp = null)

    /** An Iroh host's admin_rpc: it answers the model and conversation methods and records each call. */
    private class RecordingHost(override val hostCapabilities: Set<String> = emptySet()) : NoOpChannelTransport() {
        val methods = mutableListOf<String>()

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            methods += method
            val result = when (method) {
                "model.list" -> """[{"id":"openai/gpt-sol","handle":"openai/gpt-sol","label":"Sol"}]"""
                "conversation.get" -> """{"id":"conv-1","agent_id":"agent-1","model":"openai/gpt-sol"}"""
                else -> "{}"
            }
            return AppServerInboundFrame.AdminRpcResponse(requestId = method, success = true, result = Json.parseToJsonElement(result))
        }
    }

    private class FakeTransport : AppServerTransport {
        override val controlFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val streamFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val isConnected: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun sendControl(command: AppServerCommand) = Unit
    }

    private fun CoroutineScope.chatGateway(transport: ConversationListTransport): ChatGateway {
        var requestSequence = 0
        val client = DefaultAppServerClient(transport, parentScope = this)
        val admin = AppServerLocalAdminGateway(client) { operation -> "test-$operation-${++requestSequence}" }
        return SessionChatGateway(admin, EmptyTimelineTransport, AppServerClientChannel(client, this))
    }

    private class ConversationListTransport(
        private val rows: JsonArray,
        var failLists: Boolean = false,
    ) : AppServerTransport {
        private val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 1)
        val requests = mutableListOf<AppServerCommand.ConversationList>()
        val requestBytes = mutableListOf<Int>()

        override val controlFrames: Flow<AppServerReceivedFrame> = frames
        override val streamFrames: Flow<AppServerReceivedFrame> = emptyFlow()
        override val isConnected: Flow<Boolean> = MutableStateFlow(true)

        suspend fun pushConversation(conversation: DataConversationId, agent: AgentId) {
            val raw = buildJsonObject {
                put("type", "conversation_updated")
                put("id", "push-${conversation.value}")
                put("ts", "2026-10-28T00:00:00Z")
                put("conversation_id", conversation.value)
                put("agent_id", agent.value)
            }
            frames.emit(AppServerReceivedFrame(AppServerChannel.Control, AppServerInboundFrame.Unknown("conversation_updated", raw), raw))
        }

        override suspend fun sendControl(command: AppServerCommand) {
            val list = command as? AppServerCommand.ConversationList ?: error("unexpected App Server command")
            requests += list
            requestBytes += AppServerProtocol.encodeCommand(list).encodeToByteArray().size
            val success = !failLists
            val response = AppServerInboundFrame.ConversationListResponse(
                requestId = list.requestId,
                success = success,
                conversations = rows.takeIf { success },
                error = "conversation list unavailable".takeUnless { success },
            )
            val raw = buildJsonObject {
                put("type", "conversation_list_response")
                put("request_id", list.requestId)
                put("success", success)
                if (success) put("conversations", rows) else put("error", "conversation list unavailable")
            }
            frames.emit(AppServerReceivedFrame(AppServerChannel.Control, response, raw))
        }
    }

    private object EmptyTimelineTransport : TimelineTransport {
        override suspend fun sendConversationMessage(
            conversationId: DataConversationId,
            request: MessageCreateRequest,
        ): Flow<LettaMessage> = emptyFlow()

        override suspend fun streamConversation(conversationId: DataConversationId): Flow<TimelineStreamFrame> = emptyFlow()

        override suspend fun listConversationMessages(
            conversationId: DataConversationId,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> = emptyList()

        override suspend fun listAgentMessages(
            agentId: AgentId,
            limit: Int?,
            order: String?,
            conversationId: DataConversationId?,
        ): List<LettaMessage> = emptyList()
    }

    private object EmptyHomePageSource : HomePageSource {
        override val catalog: StateFlow<HomeSearchCatalog> = MutableStateFlow(HomeSearchCatalog())
        override val stats: StateFlow<HomeStats> = MutableStateFlow(HomeStats())

        override fun start() = Unit

        override fun refresh() = Unit

        override fun close() = Unit
    }

    private object EmptyHomePinStore : HomePinStore {
        override val pinnedKeys: StateFlow<List<String>> = MutableStateFlow(emptyList())

        override fun setOrder(keys: List<String>) = Unit

        override fun setPinned(key: String, pinned: Boolean) = Unit
    }

    private companion object {
        val AGENT_ID = AgentId("agent-0000000000000000000000000000000000000001")
        val CONVERSATION_ID_1 = DataConversationId("conv-0000000000000000000000000000000000000001")
        val CONVERSATION_ID_2 = DataConversationId("conv-0000000000000000000000000000000000000002")
    }
}

private fun conversationRow(
    id: DataConversationId,
    agentId: AgentId,
    lastMessageAt: Instant,
) = buildJsonObject {
    put("id", id.value)
    put("agent_id", agentId.value)
    put("archived", false)
    put("last_message_at", lastMessageAt.toString())
}
