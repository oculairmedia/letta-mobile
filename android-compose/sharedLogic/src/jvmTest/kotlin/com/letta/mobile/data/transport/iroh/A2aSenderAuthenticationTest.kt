package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// letta-mobile-8h07i.
/**
 * An a2a message is delivered only when the node that dialed in is the identity
 * this host holds for the message's claimed sender. Uses the real iroh key derivation.
 */
class A2aSenderAuthenticationTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "8h07i-id-${System.nanoTime()}")

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test
    fun knownNodeIdIsTheSendersPersistedIdentity() {
        val sender = IrohAgentIdentity.loadOrCreate("agent-A", dir)

        assertEquals(sender.nodeIdHex, IrohAgentIdentity.knownNodeIdHex(AgentId("agent-A"), dir))
        assertEquals(sender.nodeIdHex, IrohAgentIdentity.knownNodeIdHex(AgentId("letta_agent-A"), dir))
    }

    @Test
    fun lookupNeverCreatesAnIdentity() {
        dir.mkdirs()

        assertNull(IrohAgentIdentity.knownNodeIdHex(AgentId("agent-unknown"), dir))
        assertFalse(File(dir, "agent-unknown.json").exists(), "a claimed sender must not mint itself a key")
    }

    @Test
    fun aPathShapedSenderMatchesNothing() {
        IrohAgentIdentity.loadOrCreate("agent-A", dir)
        val nested = File(dir, "nested").apply { mkdirs() }

        assertNull(IrohAgentIdentity.knownNodeIdHex(AgentId("../agent-A"), nested))
        assertNull(IrohAgentIdentity.knownNodeIdHex(AgentId("nested/../agent-A"), dir))
    }

    @Test
    fun theSendersOwnNodeIsAuthenticatedInAnyCase() {
        val sender = IrohAgentIdentity.loadOrCreate("agent-A", dir)
        val message = message(from = "agent-A")

        assertTrue(message.isFromIdentityIn(dir, sender.nodeIdHex))
        assertTrue(message.isFromIdentityIn(dir, sender.nodeIdHex.uppercase()))
    }

    @Test
    fun anotherAgentsNodeCannotSpeakForTheSender() {
        IrohAgentIdentity.loadOrCreate("agent-A", dir)
        val impostor = IrohAgentIdentity.loadOrCreate("agent-B", dir)

        assertFalse(message(from = "agent-A").isFromIdentityIn(dir, impostor.nodeIdHex))
    }

    @Test
    fun aSenderThisHostHoldsNoIdentityForIsRefused() {
        val stranger = IrohAgentIdentity.loadOrCreate("agent-B", dir)

        assertFalse(message(from = "agent-unknown").isFromIdentityIn(dir, stranger.nodeIdHex))
        assertFalse(message(from = "agent-unknown").isFromIdentityIn(dir, ""))
    }

    @Test
    fun theReceiverRefusesAnUnauthenticatedSenderBeforeRouting() = runBlocking {
        val delivered = AtomicInteger(0)
        val receiver = receiver(delivered, authenticate = { _, _ -> false })

        val outcome = receiver.respond(message(from = "agent-A"), peer = "00ff")

        assertEquals(DeliveryOutcome(false, "sender_not_authenticated"), outcome)
        assertEquals(0, delivered.get(), "a refused message never reaches delivery")
    }

    @Test
    fun theReceiverDeliversAnAuthenticatedSender() = runBlocking {
        val sender = IrohAgentIdentity.loadOrCreate("agent-A", dir)
        val delivered = AtomicInteger(0)
        val receiver = receiver(delivered, authenticate = { message, peer -> message.isFromIdentityIn(dir, peer) })

        val outcome = receiver.respond(message(from = "agent-A"), peer = sender.nodeIdHex)

        assertTrue(outcome.delivered)
        assertEquals(1, delivered.get())
    }

    private fun receiver(delivered: AtomicInteger, authenticate: (IrohAgentMessage, String) -> Boolean) =
        IrohAgentMessageReceiver(
            router = IrohAgentMessageRouter(ownAgentId = "agent-recv"),
            conversationsFor = { emptyList() },
            onDeliver = { _, _ ->
                delivered.incrementAndGet()
                DeliveryOutcome(delivered = true)
            },
            authenticate = authenticate,
        )

    private fun message(from: String) = IrohAgentMessage(from, "agent-recv", "hi", "m-1", 1L)
}
