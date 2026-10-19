package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.model.ConversationId
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-bn008.3: OPT-IN real-loopback probe for the full receive path.
 * A sender (bn008.2) dials a receiver; the inbound message lands in the correct
 * INTERACTIVE conversation and triggers exactly one turn — and never lands in the
 * heartbeat (autonomous) conversation.
 *
 * letta-mobile-xmpqm: the receiver address is now published as ONE host
 * record, with backend membership letting the sender resolve any agent it
 * asks about. The membership gate is wired to a fake [LocalBackendAdminStore]
 * that always answers true for the test agent.
 *
 * letta-mobile-by4vm.112: the receiver is served the way the wrapper serves it,
 * as a protocol handler on the host [IrohNodeEndpoint] that also serves the App
 * Server ALPN (one endpoint, one relay registration, demultiplexed by ALPN).
 */
class IrohAgentMessageReceiveE2ETest {

    @Before fun requireOptIn() {
        assumeTrue("set -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    private lateinit var senderEp: Endpoint
    private var host: IrohNodeEndpoint? = null
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        runBlocking {
            if (::senderEp.isInitialized) runCatching { senderEp.shutdown() }
            host?.let { runCatching { it.shutdown() } }
        }
        hostScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /**
     * The host endpoint the wrapper runs: the App Server ALPN for one paired peer only, plus
     * the a2a receiver, which any host may reach.
     */
    private suspend fun startHost(receiver: IrohAgentMessageReceiver): IrohNodeEndpoint =
        IrohNodeEndpoint(
            scope = hostScope,
            authPolicy = IrohAuthPolicy.PeerAllowlist(setOf("some-other-paired-peer")),
            protocolHandlers = listOf(receiver),
        ).also {
            host = it
            it.create()
            it.start(FakeAppServerController())
        }

    private suspend fun publish(endpoint: IrohNodeEndpoint, prefix: String): HostEndpointAddressStore {
        val store = HostEndpointAddressStore(
            File.createTempFile(prefix, ".kv").apply { deleteOnExit() },
            AlwaysPresentFakeBackend(),
        )
        val addr = endpoint.addr()
        val direct = withContext(Dispatchers.IO) { addr.directAddresses() }
        store.register(IrohAgentAddress("host", endpoint.nodeIdHex(), direct))
        return store
    }

    private fun bindSender(): Endpoint =
        runBlocking { Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode())).also { it.online() } }
            .also { senderEp = it }

    private fun interactive(id: String, at: String) = IrohAgentMessageRouter.ConversationState(
        Conversation(ConversationId(id), AgentId("agent-recv"), conversationClass = ConversationClass.INTERACTIVE, lastMessageAt = at), busy = false,
    )
    private fun heartbeat(id: String, at: String) = IrohAgentMessageRouter.ConversationState(
        Conversation(ConversationId(id), AgentId("agent-recv"), conversationClass = ConversationClass.AUTONOMOUS, lastMessageAt = at), busy = false,
    )

    /**
     * E2E membership fake: every test agent resolves true; everything else
     * resolves false. The store's host record is what the resolver needs;
     * membership is the gate.
     */
    private class AlwaysPresentFakeBackend : LocalBackendAdminStore(File.createTempFile("bn008-3-fake", "")) {
        override fun agentExists(agentId: String): Boolean = true
    }

    @Test
    fun inboundLandsInInteractiveTriggersOneTurnAndSkipsHeartbeat() = runBlocking {
        bindSender()

        val turns = AtomicInteger(0)
        val landed = CompletableDeferred<String>()
        val router = IrohAgentMessageRouter(ownAgentId = "agent-recv")
        val receiver = IrohAgentMessageReceiver(
            router = router,
            conversationsFor = {
                // The heartbeat conversation is MORE recent, but must be skipped.
                listOf(
                    heartbeat("hb", "2026-07-17T12:00:00Z"),
                    interactive("chat", "2026-07-17T10:00:00Z"),
                )
            },
            onDeliver = { _, decision ->
                if (decision is IrohAgentMessageRouter.RoutingDecision.Deliver) {
                    turns.incrementAndGet()          // triggers exactly one turn
                    landed.complete(decision.conversationId)
                }
                DeliveryOutcome(delivered = decision is IrohAgentMessageRouter.RoutingDecision.Deliver)
            },
        )
        val endpoint = startHost(receiver)

        // Publish the host record (ONE kv line per letta-mobile-xmpqm) + send.
        val store = publish(endpoint, "bn008-3")
        val sender = IrohAgentMessageSender(senderEp, IrohAgentAddressResolver(store))

        val initialEventCount = com.letta.mobile.util.Telemetry.events.value.size
        val result = sender.send(IrohAgentMessage("agent-sender", "agent-recv", "hi", "m-1", 1L))
        assertIs<AgentSendResult.Delivered>(result)

        val landedConv = withTimeout(15.seconds) { landed.await() }
        assertEquals("chat", landedConv, "must land in the INTERACTIVE conversation, not the heartbeat")
        assertEquals(1, turns.get(), "exactly one turn triggered")
        // letta-mobile-5nspp: the receiver MUST emit a2a.recv BEFORE ack/decision,
        // so the sensor sees the inbound even if downstream drops/rewrites it.
        // The recv and route telemetry must pair 1:1 (a route without a recv is a bug).
        val recvEvents = com.letta.mobile.util.Telemetry.events.value
            .drop(initialEventCount)
            .filter { it.name == "a2a.recv" && it.attrs["msgId"] == "m-1" }
        assertEquals(
            1,
            recvEvents.size,
            "exactly one a2a.recv event for the inbound message; got: $recvEvents",
        )
        assertEquals("agent-sender", recvEvents[0].attrs["fromAgentId"])
        assertEquals("agent-recv", recvEvents[0].attrs["toAgentId"])
        assertEquals("m-1", recvEvents[0].attrs["msgId"])
        Unit
    }

    /**
     * letta-mobile-5m1qy: an inbound envelope WITH conversationId lands the
     * deliver decision on that EXACT conversation — not on the most-recent
     * interactive one.
     */
    @Test
    fun inboundWithExplicitConversationIdLandsOnThatExactConversation() = runBlocking {
        bindSender()

        val turns = AtomicInteger(0)
        val landed = CompletableDeferred<String>()
        val router = IrohAgentMessageRouter(ownAgentId = "agent-recv")
        val receiver = IrohAgentMessageReceiver(
            router = router,
            conversationsFor = {
                listOf(
                    heartbeat("hb", "2026-07-17T12:00:00Z"),
                    interactive("chat", "2026-07-17T11:00:00Z"),
                    // The explicit target is the OLDEST interactive conversation.
                    interactive("working-conv", "2026-07-17T09:00:00Z"),
                )
            },
            onDeliver = { _, decision ->
                if (decision is IrohAgentMessageRouter.RoutingDecision.Deliver) {
                    turns.incrementAndGet()
                    landed.complete(decision.conversationId)
                }
                DeliveryOutcome(delivered = decision is IrohAgentMessageRouter.RoutingDecision.Deliver)
            },
        )
        val endpoint = startHost(receiver)

        // Publish the host record (ONE kv line per letta-mobile-xmpqm) + send.
        val store = publish(endpoint, "5m1qy")
        val sender = IrohAgentMessageSender(senderEp, IrohAgentAddressResolver(store))

        val result = sender.send(
            IrohAgentMessage("agent-sender", "agent-recv", "hi", "m-target", 1L, conversationId = "working-conv"),
        )
        assertIs<AgentSendResult.Delivered>(result)

        val landedConv = withTimeout(15.seconds) { landed.await() }
        assertEquals("working-conv", landedConv, "must land on the explicit target conversation, not the most-recent one")
        assertEquals(1, turns.get(), "exactly one turn triggered")
        Unit
    }

    /**
     * letta-mobile-by4vm.112: one host endpoint negotiates BOTH the App Server and the a2a ALPN
     * for the same node id, so no dial (relay-routed or direct) can reach an endpoint that lacks
     * the App Server protocol. An ALPN the host does not serve still fails the handshake.
     */
    @Test
    fun hostEndpointNegotiatesAppServerAndA2aAlpns() = runBlocking {
        val sender = bindSender()
        val receiver = IrohAgentMessageReceiver(
            router = IrohAgentMessageRouter(ownAgentId = "agent-recv"),
            conversationsFor = { emptyList() },
            onDeliver = { _, _ -> DeliveryOutcome(delivered = false) },
        )
        val endpoint = startHost(receiver)
        for (alpn in listOf(IrohNodeEndpoint.DEFAULT_ALPN, IrohAgentMessage.ALPN)) {
            val connection = withTimeout(15.seconds) { sender.connect(endpoint.addr(), alpn) }
            assertEquals(alpn.decodeToString(), connection.alpn().decodeToString())
            connection.close(0L, ByteArray(0))
        }
        val refused = runCatching {
            withTimeout(15.seconds) { sender.connect(endpoint.addr(), "/letta/unknown/0".encodeToByteArray()) }
        }
        kotlin.test.assertTrue(refused.isFailure, "an unserved ALPN must not complete the handshake")
        Unit
    }
}
