package com.letta.mobile.data.transport.iroh

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
import kotlinx.coroutines.Job
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
 */
class IrohAgentMessageReceiveE2ETest {

    @Before fun requireOptIn() {
        assumeTrue("set -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    private lateinit var senderEp: Endpoint
    private lateinit var receiverEp: Endpoint

    @After fun tearDown() {
        runBlocking {
            if (::senderEp.isInitialized) runCatching { senderEp.shutdown() }
            if (::receiverEp.isInitialized) runCatching { receiverEp.shutdown() }
        }
    }

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
        senderEp = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode()))
        receiverEp = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), alpns = listOf(IrohAgentMessage.ALPN)))
        receiverEp.online(); senderEp.online()

        val turns = AtomicInteger(0)
        val landed = CompletableDeferred<String>()
        val router = IrohAgentMessageRouter(ownAgentId = "agent-recv")
        val receiver = IrohAgentMessageReceiver(
            endpoint = receiverEp,
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = receiver.start(scope)

        // Publish the host record (ONE kv line per letta-mobile-xmpqm) + send.
        val store = HostEndpointAddressStore(
            File.createTempFile("bn008-3", ".kv").apply { deleteOnExit() },
            AlwaysPresentFakeBackend(),
        )
        val addr = receiverEp.addr()
        val nodeHex = addr.id().toBytes().joinToString("") { "%02x".format(it) }
        val direct = withContext(Dispatchers.IO) { addr.directAddresses() }
        store.register(IrohAgentAddress("host", nodeHex, direct))
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
        job.cancel()
        scope.coroutineContext[Job]?.cancel()
        Unit
    }

    /**
     * letta-mobile-5m1qy: an inbound envelope WITH conversationId lands the
     * deliver decision on that EXACT conversation — not on the most-recent
     * interactive one.
     */
    @Test
    fun inboundWithExplicitConversationIdLandsOnThatExactConversation() = runBlocking {
        senderEp = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode()))
        receiverEp = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), alpns = listOf(IrohAgentMessage.ALPN)))
        receiverEp.online(); senderEp.online()

        val turns = AtomicInteger(0)
        val landed = CompletableDeferred<String>()
        val router = IrohAgentMessageRouter(ownAgentId = "agent-recv")
        val receiver = IrohAgentMessageReceiver(
            endpoint = receiverEp,
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = receiver.start(scope)

        // Publish the host record (ONE kv line per letta-mobile-xmpqm) + send.
        val store = HostEndpointAddressStore(
            File.createTempFile("5m1qy", ".kv").apply { deleteOnExit() },
            AlwaysPresentFakeBackend(),
        )
        val addr = receiverEp.addr()
        val nodeHex = addr.id().toBytes().joinToString("") { "%02x".format(it) }
        val direct = withContext(Dispatchers.IO) { addr.directAddresses() }
        store.register(IrohAgentAddress("host", nodeHex, direct))
        val sender = IrohAgentMessageSender(senderEp, IrohAgentAddressResolver(store))

        val result = sender.send(
            IrohAgentMessage("agent-sender", "agent-recv", "hi", "m-target", 1L, conversationId = "working-conv"),
        )
        assertIs<AgentSendResult.Delivered>(result)

        val landedConv = withTimeout(15.seconds) { landed.await() }
        assertEquals("working-conv", landedConv, "must land on the explicit target conversation, not the most-recent one")
        assertEquals(1, turns.get(), "exactly one turn triggered")
        job.cancel()
        scope.coroutineContext[Job]?.cancel()
        Unit
    }
}
