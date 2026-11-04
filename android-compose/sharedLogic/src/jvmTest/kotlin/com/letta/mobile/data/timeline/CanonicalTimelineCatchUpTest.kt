package com.letta.mobile.data.timeline

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ConversationAdminHandlers
import com.letta.mobile.data.controller.node.iroh.IrohConnectionAuthentication
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.NativeReadTiers
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import computer.iroh.Connection
import computer.iroh.NoHandle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Real gateway -> wrapper message.list handler -> session -> Pager -> screen rows. No live host. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CanonicalTimelineCatchUpTest {
    @Test fun switchingBackRecoversEveryPersistedMessageWhenTheViewerMissedTheTerminal() = runTest {
        assertEquals(setOf("old", "prompt", "reason", "reply", "followup"), recoverMissedTurn(reconnect = false))
    }

    @Test fun wrapperRestartMidTurnThenReattachAndSyncRecoversEveryPersistedMessage() = runTest {
        assertEquals(setOf("old", "prompt", "reason", "reply", "followup"), recoverMissedTurn(reconnect = true))
    }

    @Test fun wrapperAcceptsTheRealClientsNestedRuntimeSyncAndRejectsMissingScope() = runTest {
        val host = TranscriptHost()
        val runtime = AppServerRuntimeScope("agent", "A")
        val response = host.command(AppServerCommand.Sync(runtime, "sync"))
        assertEquals("true", response["success"]?.jsonPrimitive?.content,
            "the production codec's runtime-scoped sync must reach controller.sync: $response")
        assertEquals(listOf(runtime), host.synced)
        val decoded = AppServerProtocol.decodeFrame(response.toString()).frame
        assertTrue(decoded is AppServerInboundFrame.SyncResponse, "sync_response must carry its runtime: $decoded")
        val missing = host.control("handleControlSync", buildJsonObject { put("request_id", "missing") })
        assertEquals("false", missing["success"]?.jsonPrimitive?.content)
        assertEquals(listOf(runtime), host.synced, "missing scope must not reach the controller")
    }

    @Test fun wrapperRetainsLegacyFlatSyncAndNeverCombinesTwoScopes() = runTest {
        val host = TranscriptHost()
        val flat = host.control("handleControlSync", buildJsonObject {
            put("request_id", "legacy")
            put("agent_id", "agent")
            put("conversation_id", "A")
        })
        assertEquals("true", flat["success"]?.jsonPrimitive?.content)
        assertTrue(AppServerProtocol.decodeFrame(flat.toString()).frame is AppServerInboundFrame.SyncResponse)
        val mixed = host.control("handleControlSync", buildJsonObject {
            put("request_id", "mixed")
            put("agent_id", "other-agent")
            put("runtime", buildJsonObject { put("conversation_id", "B") })
        })
        assertEquals("false", mixed["success"]?.jsonPrimitive?.content)
        assertEquals(listOf(AppServerRuntimeScope("agent", "A")), host.synced)
    }

    private suspend fun TestScope.recoverMissedTurn(reconnect: Boolean): Set<String> {
        val host = TranscriptHost()
        val gateway = IrohAdminRpcChatGateway(host)
        val coordinator = CanonicalTimelineCoordinator(scopedStore(), gateway)
        val owner = coordinator.acquire(TimelineScope("iroh", "A", "agent"))
        val ui = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            var screen = CanonicalTimelinePresentation.open(coordinator, owner, ui)
            var presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>(StandardTestDispatcher(testScheduler))
            var collector = ui.launch { screen.settled.collectLatest { presenter.collectFrom(it) } }
            advanceUntilIdle()
            assertEquals(1, presenter.size,
                "state=${presenter.loadStateFlow.value} reads=${host.reads}")

            // A starts streaming while selected; the writer and its unfinished fence outlive the view.
            val prefix = reply("reply", "first ", 3)
            coordinator.ingestExternal(owner, hostStamped(prefix))
            advanceUntilIdle()
            assertFalse(owner.session.live.value?.block?.terminal ?: true)
            collector.cancel()
            screen.close()

            if (reconnect) {
                // A restarted wrapper has no parked frames or reattached runtime inventory.
                host.restartWrapper()
            } else {
                val other = coordinator.acquire(TimelineScope("iroh", "B", "agent"))
                val otherScreen = CanonicalTimelinePresentation.open(coordinator, other, ui)
                val otherPresenter = RecordingPresenter<CanonicalTimelinePresentation.Row>(StandardTestDispatcher(testScheduler))
                val otherCollector = ui.launch { otherScreen.settled.collectLatest { otherPresenter.collectFrom(it) } }
                advanceUntilIdle()
                otherCollector.cancel()
                otherScreen.close()
            }

            // The turn persists while A has no viewer. Its terminal (error/CancellationException)
            // is lost; no Done, parked replay, or replacement live frames reach this coordinator.
            host.messages.getValue("A") += listOf(
                UserMessage(id = "prompt", contentRaw = JsonPrimitive("question"), date = date(1)),
                ReasoningMessage(id = "reason", reasoning = "considering", date = date(2)),
                prefix.copy(contentRaw = JsonPrimitive("first complete reply")),
                reply("followup", "second complete reply", 4),
            )
            val requestsBeforeOpen = host.reads.size
            val sync = if (reconnect) {
                host.command(AppServerCommand.RuntimeStart("reattach", agentId = "agent", conversationId = "A"))
                host.command(AppServerCommand.Sync(AppServerRuntimeScope("agent", "A"), "sync-after-restart"))
            } else null

            // This is the same retained owner DesktopCanonicalTimelineRuntime.open reuses.
            screen = CanonicalTimelinePresentation.open(coordinator, owner, ui)
            presenter = RecordingPresenter(StandardTestDispatcher(testScheduler))
            collector = ui.launch { screen.settled.collectLatest { presenter.collectFrom(it) } }
            advanceUntilIdle()
            assertCaughtUp(presenter)
            assertResidentRowsReplaceStalePrefix(screen, presenter)
            assertTrue(host.reads.drop(requestsBeforeOpen).all { it == "A" }, "catch up only the selected conversation")
            assertTrue(host.reads.size - requestsBeforeOpen in 1..4, "bounded recent-page catch-up")
            assertFalse(owner.session.live.value?.block?.terminal ?: true, "history must not invent a terminal")
            if (sync != null) assertEquals("true", sync["success"]?.jsonPrimitive?.content)
            collector.cancel()
            screen.close()
            return presenter.snapshot().items.flatMap { it.residentEvents }.map { it.serverId }.toSet()
        } finally {
            ui.cancel()
        }
    }

    private fun TestScope.assertResidentRowsReplaceStalePrefix(
        screen: CanonicalTimelinePresentation,
        presenter: RecordingPresenter<CanonicalTimelinePresentation.Row>,
    ) {
        screen.onResidentRows(presenter.snapshot().items)
        advanceUntilIdle()
        assertTrue(screen.live.value.isEmpty(), "resident persisted rows replace the stale live prefix")
    }

    private fun scopedStore(): TimelineBoundedStore {
        val stores = mutableMapOf<TimelineScope, InMemoryTimelineStore>()
        return object : TimelineBoundedStore {
            override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
                stores.getOrPut(scope) { InMemoryTimelineStore() }.read(scope, block)
            override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
                stores.getOrPut(scope) { InMemoryTimelineStore() }.transaction(scope, block)
        }
    }

    private fun assertCaughtUp(presenter: RecordingPresenter<CanonicalTimelinePresentation.Row>) {
        val rows = presenter.snapshot().items
        val events = rows.flatMap { it.residentEvents }.map { it.serverId }
        assertEquals(setOf("old", "prompt", "reason", "reply", "followup"), events.toSet(),
            "opening/resuming A must display every persisted message despite its missed terminal")
        assertEquals(5, events.size, "each persisted message appears exactly once")
        val contents = rows.flatMap { row -> when (val item = row.item) {
            is com.letta.mobile.data.chat.projection.ChatRenderItem.Single -> listOf(item.message.content)
            is com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock -> item.messages.map { it.first.content }
        } }
        assertTrue("first complete reply" in contents, "persisted final replaces the live prefix: $contents")
        assertTrue("second complete reply" in contents)
        presenter.assertIdle()
    }

    /** Only storage/controller effects are fake; both RPC and sync validation are the real wrapper's. */
    private class TranscriptHost : NoOpChannelTransport() {
        val messages = mutableMapOf("A" to mutableListOf<LettaMessage>(reply("old", "earlier reply", 0)),
            "B" to mutableListOf<LettaMessage>())
        val reads = mutableListOf<String>()
        val synced = mutableListOf<AppServerRuntimeScope>()
        private val native = proxy<AppServerClient> { name, args ->
            check(name == "conversationMessagesList") { "Unexpected native call: $name" }
            val request = args[0] as AppServerCommand.ConversationMessagesList
            reads += request.conversationId
            val limit = requireNotNull(request.query)["limit"]?.jsonPrimitive?.content?.toInt() ?: error("unbounded read")
            check(limit in 1..64)
            check(requireNotNull(request.query)["order"]?.jsonPrimitive?.content == "desc")
            val page = messages.getValue(request.conversationId).asReversed().take(limit)
            val encoded = com.letta.mobile.data.serialization.LettaJson.persisted
                .encodeToJsonElement(ListSerializer(LettaMessage.serializer()), page) as kotlinx.serialization.json.JsonArray
            AppServerInboundFrame.ConversationMessagesListResponse(request.requestId, true, encoded)
        }
        private val controller = proxy<AppServerController> { name, args -> when {
            name.startsWith("startRuntime") -> CanonicalRuntime(AppServerRuntimeScope(args[0] as String, args[1] as String))
            name == "sync" -> (args[0] as AppServerRuntimeScope).let {
                synced += it
                AppServerInboundFrame.SyncResponse("upstream", it, true)
            }
            else -> error("Unexpected controller call: $name")
        } }
        private var node = newNode()

        private fun newNode(): IrohNodeConnection = IrohNodeConnection(
            Connection(NoHandle), controller,
            AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = native)) },
            IrohConnectionAuthentication(IrohAuthPolicy.InsecureAnonymousForTestOnly),
        )

        fun restartWrapper() { node = newNode() }

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val response = control("handleControlAdminRpc", buildJsonObject {
                put("request_id", "history")
                put("method", method)
                put("params", appServerAdminRpcParams(path, body))
            })
            return AppServerProtocol.json.decodeFromJsonElement(AppServerInboundFrame.AdminRpcResponse.serializer(), response)
        }

        suspend fun command(command: AppServerCommand): JsonObject {
            val wire = AppServerProtocol.json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject
            return if (command is AppServerCommand.RuntimeStart) control("handleRuntimeStart", wire, command.requestId)
            else control("handleControlSync", wire)
        }

        suspend fun control(name: String, wire: JsonObject, requestId: String? = null): JsonObject = suspendCoroutine { continuation ->
            val types = if (requestId == null) arrayOf(JsonObject::class.java, Continuation::class.java)
                else arrayOf(JsonObject::class.java, String::class.java, Continuation::class.java)
            val method = IrohNodeConnection::class.java.getDeclaredMethod(name, *types).also { it.isAccessible = true }
            val decoded = object : Continuation<String> {
                override val context = continuation.context
                override fun resumeWith(result: Result<String>) = continuation.resumeWith(result.map {
                    AppServerProtocol.json.parseToJsonElement(it).jsonObject
                })
            }
            try {
                val result = if (requestId == null) method.invoke(node, wire, decoded)
                    else method.invoke(node, wire, requestId, decoded)
                if (result !== COROUTINE_SUSPENDED) {
                    continuation.resume(AppServerProtocol.json.parseToJsonElement(result as String).jsonObject)
                }
            } catch (failure: InvocationTargetException) {
                continuation.resumeWithException(failure.targetException)
            }
        }
    }

    companion object {
        private fun date(second: Int) = "2026-11-04T22:12:0${second}Z"
        private fun reply(id: String, content: String, second: Int) =
            AssistantMessage(id = id, contentRaw = JsonPrimitive(content), date = date(second))

        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T
    }
}
