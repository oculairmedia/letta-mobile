package com.letta.mobile.data.node

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId as ModelConversationId
import com.letta.mobile.data.node.carrier.InProcessNodeCarrier
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeCredential
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeInbound
import com.letta.mobile.data.node.carrier.NodeLane
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodeOpenRequest
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.data.node.carrier.NodeSession
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The real node behind the in-process carrier, driven through [NodeSession]: the node is [NodeComposition]'s
 * endpoint, router (with its host-held routes and the durable feed) and auth, serving each link as the same node
 * connection an Iroh peer gets. Only the App Server is fake: its turns come from a script, since no live App Server
 * runs here, and the endpoint's Iroh socket is never bound. The scenarios follow the carrier contract's six.
 */
class InProcessNodeRealNodeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateDir: File = Files.createTempDirectory("in-process-node").toFile()
    private val pairing = IrohPairingService(
        InMemoryPairedPeerStore().apply {
            save(PairedPeer(READER.value, "reader", 0, setOf(IrohPeerCapabilities.CHAT_READ), null))
        },
    )

    /** Turns the fake App Server runs, in order; each is told when to finish. */
    private val turns = Channel<suspend kotlinx.coroutines.flow.FlowCollector<RuntimeEventDraft>.() -> Unit>(Channel.UNLIMITED)

    private val appServer: AppServerController = object : AppServerController by FakeAppServerController() {
        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow { turns.receive().invoke(this) }
    }

    private val node = NodeComposition(
        config = NodeConfig(
            modelExposureFile = File(stateDir, "model-exposure.json").path,
            requestTimeoutMs = 1_000,
            endpoint = NodeEndpointConfig(bindAddr = "127.0.0.1:0"),
        ),
        ports = NodePorts(
            httpClients = JvmAppServerHttpClients,
            log = object : NodeLog {
                override fun info(line: String) = Unit

                override fun error(line: String) = Unit
            },
        ),
        scope = scope,
    )

    private val endpoint = node.endpoint(NodeEndpointParts(authPolicy = IrohAuthPolicy.PairedPeersOnly, pairingService = pairing))

    init {
        val connection = node.connect(appServer = null, tools = ExternalToolRegistry.factoryDefault())
        node.installAdminRpc(endpoint, connection, NodeAdminExtras(pairingService = pairing))
        // What `start` does minus binding Iroh: the endpoint's turn host, serving the fake App Server's turns.
        endpoint.startTurnHost(appServer)
    }

    /** The shell's carrier into this node. */
    private val local = node.inProcessCarrier(endpoint, NODE)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        stateDir.deleteRecursively()
    }

    @Test
    fun auth() = scenario {
        val session = open()
        assertEquals(NODE, session.node, "the shell sees the node's own identity")
        assertTrue(
            session.capabilities.supports("frame_part") && session.capabilities.supports("conversation_feed_v1"),
            "${session.capabilities}",
        )
        assertEquals(false, session.capabilities.paired, "the local shell is admitted by construction, not paired")

        // A peer the carrier reports as someone else meets the node's own policy: unpaired, no token, refused.
        val refused = runCatching { open(asPeer(STRANGER)) }.exceptionOrNull()
        assertEquals(NodeCarrierFailure.AuthRejected("pairing_required"), (refused as? NodeCarrierException)?.failure)

        // Local-shell admission belongs to the in-process carrier's own acceptor, not to the peer label: a link that
        // reports LOCAL_SHELL through the general acceptor is refused without a token like any other peer.
        val impostor = runCatching { open(asPeer(NodePeerIdentity.LOCAL_SHELL)) }.exceptionOrNull()
        assertEquals(NodeCarrierFailure.AuthRejected("pairing_required"), (impostor as? NodeCarrierException)?.failure)

        session.close()
        assertEquals(NodeLinkClosure.NORMAL, session.closure.value)
    }

    @Test
    fun aNodeThatStopsLosesTheLink() = scenario {
        // The session's routing runs in a scope of its own, so only the node stops.
        val shell = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = NodeSession.open(local, NodeOpenRequest(local.address, NodeCredential.CarrierIdentity), shell)
        scope.cancel()
        assertEquals(NodeLinkClosure.LOST, session.closure.first { it != null }, "a stopped node is a lost link, not a detach")
        shell.cancel()
    }

    @Test
    fun capabilityGate() = scenario {
        // A paired peer authenticates by its carrier identity alone: the open succeeds with no token. The node reports
        // `paired` only when an invite is redeemed, so an already-paired peer signing in is not flagged.
        val reader = open(asPeer(READER))
        assertEquals(false, reader.capabilities.paired, "an existing pairing signs in without being reported as newly paired")

        val deniedCall = reader.adminRpc("pins.set", buildJsonObject { put("agent_ids", buildJsonArray { }) }).json
        assertEquals(false, deniedCall.bool("success"))
        assertEquals("forbidden", deniedCall.text("error"))
        assertTrue(deniedCall.text("capability") != null, "a denial names the missing capability: $deniedCall")

        val deniedCommand = reader.request(runtimeStart(reader.nextRequestId())).json
        assertEquals("error", deniedCommand.text("type"))
        assertEquals(IrohPeerCapabilities.CHAT_SEND, deniedCommand.text("capability"))

        assertEquals(true, reader.adminRpc("health.check", null).json.bool("success"), "a permitted call still succeeds")

        val admin = open()
        assertEquals(true, admin.request(runtimeStart(admin.nextRequestId())).json.bool("success"), "the local shell holds every grant")
    }

    @Test
    fun adminRpc() = scenario {
        val session = open()
        val (health, unknown) = listOf("health.check", "no.such.method").map { method ->
            async { session.adminRpc(method, null).json }
        }.awaitAll()
        assertEquals(true, health.bool("success"), "$health")
        assertTrue(health["result"]?.jsonObject?.containsKey("status") == true, "$health")
        assertEquals(false, unknown.bool("success"), "an unknown method is a response, not a carrier failure: $unknown")
    }

    @Test
    fun turn() = scenario {
        val session = open()
        val pushes = collectPushes(session)
        assertEquals(true, session.request(runtimeStart(session.nextRequestId())).json.bool("success"))

        turns.send { emit(remote(assistant("Hi"))); emit(remote(stop())); emit(lifecycle(RuntimeRunStatus.Completed)) }
        val accepted = session.request(input(session.nextRequestId(), "cm-turn")).json
        assertEquals("input_accepted", accepted.text("type"))

        val assistant = awaitDelta(pushes, "assistant_message")
        assertEquals("Hi", assistant.text("content"))
        awaitDelta(pushes, "stop_reason")
    }

    @Test
    fun detach() = scenario {
        val first = open()
        val start = first.resumeFeed(ModelConversationId(CONVERSATION), AgentId(AGENT), from = null).last().cursor
        assertEquals(true, first.request(runtimeStart(first.nextRequestId())).json.bool("success"))
        val pushes = collectPushes(first)
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        turns.send {
            emit(remote(assistant("before")))
            release.await()
            emit(remote(assistant("after-detach")))
            emit(remote(stop()))
            emit(lifecycle(RuntimeRunStatus.Completed))
            finished.complete(Unit)
        }
        first.request(input(first.nextRequestId(), "cm-detach"))
        awaitDelta(pushes, "assistant_message")

        first.close()
        assertEquals(NodeLinkClosure.NORMAL, first.closure.value, "closing mid-turn is an orderly detach")
        release.complete(Unit)
        finished.await()

        val second = open()
        val rest = feedText(second, start)
        assertTrue("after-detach" in rest, "the detached turn kept running and its rest is in the feed: $rest")
    }

    @Test
    fun feedResume() = scenario {
        val session = open()
        assertEquals(true, session.request(runtimeStart(session.nextRequestId())).json.bool("success"))
        runTurn(session, "cm-feed-1", "one")
        val snapshot = session.resumeFeed(ModelConversationId(CONVERSATION), AgentId(AGENT), from = null)
        assertEquals("snapshot", snapshot.first().mode)
        assertTrue("one" in snapshot.flatMap { it.entries }.toString(), "$snapshot")

        runTurn(session, "cm-feed-2", "two")
        val replay = session.resumeFeed(ModelConversationId(CONVERSATION), AgentId(AGENT), from = snapshot.last().cursor)
        assertEquals("replay", replay.first().mode)
        val replayed = replay.flatMap { it.entries }.toString()
        assertTrue("two" in replayed && "\"one\"" !in replayed, "a replay continues from the cursor: $replayed")

        val refused = runCatching {
            session.resumeFeed(ModelConversationId(CONVERSATION), AgentId(AGENT), from = FeedCursor(epoch = -1, seq = -1))
        }.exceptionOrNull()
        assertTrue((refused as? NodeCarrierException)?.failure is NodeCarrierFailure.Refused, "$refused")
    }

    private fun scenario(body: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(20.seconds) { body() }
    }

    private suspend fun open(carrier: InProcessNodeCarrier = local): NodeSession =
        NodeSession.open(carrier, NodeOpenRequest(carrier.address, NodeCredential.CarrierIdentity), scope)

    /** The same in-process link, but reported to the node as [peer]: how a remote peer's link looks to it. */
    private fun asPeer(identity: NodePeerIdentity): InProcessNodeCarrier {
        val acceptor = node.peerAcceptor(endpoint)
        val relabelled = NodePeerAcceptor { link ->
            acceptor.serve(object : NodePeerLink by link {
                override val peer: NodePeerIdentity = identity
            })
        }
        return InProcessNodeCarrier("as-${identity.value}", NODE, relabelled, scope)
    }

    private fun collectPushes(session: NodeSession): Channel<NodeFrame> {
        val pushes = Channel<NodeFrame>(Channel.UNLIMITED)
        scope.launch { session.inbound.collect { inbound: NodeInbound -> if (inbound.lane == NodeLane.PUSH) pushes.send(inbound.frame) } }
        return pushes
    }

    private suspend fun awaitDelta(pushes: Channel<NodeFrame>, messageType: String): JsonObject {
        while (true) {
            val frame = pushes.receive().json
            val delta = frame["delta"] as? JsonObject ?: continue
            if (frame.text("type") == "stream_delta" && delta.text("message_type") == messageType) return delta
        }
    }

    private suspend fun runTurn(session: NodeSession, clientMessageId: String, text: String) {
        val finished = CompletableDeferred<Unit>()
        turns.send {
            emit(remote(assistant(text)))
            emit(remote(stop()))
            emit(lifecycle(RuntimeRunStatus.Completed))
            finished.complete(Unit)
        }
        session.request(input(session.nextRequestId(), clientMessageId))
        finished.await()
    }

    private suspend fun feedText(session: NodeSession, from: FeedCursor): String {
        // The detached turn's tail is journaled as it runs; read until it is there or the scenario times out.
        while (true) {
            val text = session.resumeFeed(ModelConversationId(CONVERSATION), AgentId(AGENT), from).flatMap { it.entries }.toString()
            if ("after-detach" in text) return text
            kotlinx.coroutines.delay(50)
        }
    }

    private companion object {
        const val AGENT = "agent-a"
        const val CONVERSATION = "conv-1"
        const val RUN = "run-1"
        val NODE = NodePeerIdentity("node-under-test")
        val READER = NodePeerIdentity("b".repeat(64))
        val STRANGER = NodePeerIdentity("c".repeat(64))
        val runtime = AppServerRuntimeScope(AGENT, CONVERSATION)
        private val runtimeJson = AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime)
        private var seq = 0L

        fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeUnless { this[key] is kotlinx.serialization.json.JsonNull }

        fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.boolean }.getOrNull()

        fun runtimeStart(requestId: String) = NodeFrame(
            buildJsonObject {
                put("type", "runtime_start")
                put("request_id", requestId)
                put("agent_id", AGENT)
                put("conversation_id", CONVERSATION)
            },
        )

        fun input(requestId: String, clientMessageId: String) = NodeFrame(
            buildJsonObject {
                put("type", "input")
                put("request_id", requestId)
                put("runtime", runtimeJson)
                put(
                    "payload",
                    buildJsonObject {
                        put("kind", "create_message")
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", "hello")
                                        put("client_message_id", clientMessageId)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        fun streamDelta(fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            seq += 1
            put("type", "stream_delta")
            put("runtime", runtimeJson)
            put("idempotency_key", "inproc-$seq")
            put("event_seq", seq)
            put("emitted_at", "2026-11-26T00:00:00Z")
            put(
                "delta",
                buildJsonObject {
                    put("run_id", RUN)
                    fields()
                },
            )
        }

        fun assistant(text: String) = streamDelta {
            put("message_type", "assistant_message")
            put("message_id", "msg-$text")
            put("content", text)
        }

        fun stop() = streamDelta {
            put("message_type", "stop_reason")
            put("stop_reason", "end_turn")
        }

        fun draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:$AGENT:$CONVERSATION"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            runId = RunId(RUN),
            source = RuntimeEventSource.LocalRuntime,
            payload = payload,
        )

        fun remote(frame: JsonObject) = draft(
            RuntimeEventPayload.RemoteStreamFrame(frameId = frame.text("idempotency_key")!!, body = frame.toString()),
        )

        fun lifecycle(status: RuntimeRunStatus) = draft(RuntimeEventPayload.RunLifecycleChanged(status = status, reason = null))
    }
}
