package com.letta.mobile.data.transport.iroh

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

/**
 * letta-mobile-bn008.3: OPT-IN real-loopback probe for the full receive path.
 * A sender (bn008.2) dials a receiver; the inbound message lands in the correct
 * INTERACTIVE conversation and triggers exactly one turn — and never lands in the
 * heartbeat (autonomous) conversation.
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
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = receiver.start(scope)

        // Publish the receiver address + send.
        val store = FileIrohAgentAddressStore(File.createTempFile("bn008-3", ".kv").apply { deleteOnExit() })
        val addr = receiverEp.addr()
        val nodeHex = addr.id().toBytes().joinToString("") { "%02x".format(it) }
        val direct = withContext(Dispatchers.IO) { addr.directAddresses() }
        store.register(IrohAgentAddress("agent-recv", nodeHex, direct))
        val sender = IrohAgentMessageSender(senderEp, IrohAgentAddressResolver(store))

        val result = sender.send(IrohAgentMessage("agent-sender", "agent-recv", "hi", "m-1", 1L))
        assertIs<AgentSendResult.Delivered>(result)

        val landedConv = withTimeout(15_000) { landed.await() }
        assertEquals("chat", landedConv, "must land in the INTERACTIVE conversation, not the heartbeat")
        assertEquals(1, turns.get(), "exactly one turn triggered")
        job.cancel()
        scope.coroutineContext[Job]?.cancel()
        Unit
    }
}
