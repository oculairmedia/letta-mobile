package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Lanes v1: a send addressed by id to a lane:work conversation is dropped AND the sender is
 * told why. Drives the real receiver (router + respond) into the real ack classification the
 * sender runs; asserting only that the work conversation received nothing would pass even if
 * the sender saw a silent success.
 */
class LaneDropReachesSenderTest {
    private fun state(id: String, tag: String) = IrohAgentMessageRouter.ConversationState(
        Conversation(ConversationId(id), AgentId("target"), tags = listOf(tag)), busy = false,
    )

    private val delivered = mutableListOf<String>()
    private val observed = mutableListOf<IrohAgentMessage>()
    private val identityDir = kotlin.io.path.createTempDirectory("lane-identities").toFile()
    private val senderIdentity = IrohAgentIdentity.loadOrCreate("other", identityDir)

    @AfterTest
    fun cleanupIdentities() { identityDir.deleteRecursively() }

    private val receiver = IrohAgentMessageReceiver(
        router = IrohAgentMessageRouter("target"),
        conversationsFor = { agentId ->
            if (agentId == "target") {
                listOf(state("work-1", "lane:work"), state("home-conv", "lane:home"), state("inbox-1", "lane:inbox"))
            } else {
                emptyList()
            }
        },
        onDeliver = { message, decision ->
            observed += message
            when (decision) {
                is IrohAgentMessageRouter.RoutingDecision.Deliver -> {
                    delivered += decision.conversationId
                    DeliveryOutcome(true)
                }
                is IrohAgentMessageRouter.RoutingDecision.Dropped -> DeliveryOutcome(false, decision.reason)
                else -> error("unexpected $decision")
            }
        },
        authenticate = { message, peer -> message.isFromIdentityIn(identityDir, peer) },
    )

    private val sender = IrohAgentMessageSender(
        endpointProvider = { error("no dial in this test") },
        resolver = IrohAgentAddressResolver(
            HostEndpointAddressStore(
                java.io.File.createTempFile("lane-drop", ".kv").apply { deleteOnExit() },
                object : com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore(
                    java.io.File.createTempFile("lane-drop-backend", ""),
                ) {
                    override fun agentExists(agentId: String): Boolean = true
                },
            ),
        ),
    )

    private fun senderSees(message: IrohAgentMessage): AgentSendResult = runBlocking {
        val outcome = receiver.respond(message, senderIdentity.nodeIdHex)
        sender.classifyAck(
            message,
            IrohAgentMessageAck(message.msgId, accepted = true, applicationDelivered = outcome.delivered, reason = outcome.reason),
        )
    }

    @Test
    fun sendToWorkConversationByIdIsRefusedAndTheSenderSeesWhy() {
        val result = senderSees(IrohAgentMessage("other", "target", "hi", "m-1", 1L, conversationId = "work-1"))
        val accepted = assertIs<AgentSendResult.Accepted>(result)
        assertEquals("target_is_work_lane", accepted.reason)
        assertTrue(delivered.isEmpty(), "nothing may reach any conversation")
    }

    @Test
    fun deniedLaneResultsAndRetriesKeepIdentityAndCorrelation() {
        val correlation = mapOf("bead" to "x=y", "moleculeRun" to "42")
        val messages = listOf(
            IrohAgentMessage("other", "target", "hi", "home-1", 1L, lane = "home", correlation = correlation) to
                "home_lane_forbidden",
            IrohAgentMessage("other", "target", "hi", "home-2", 1L, conversationId = "home-conv", correlation = correlation) to
                "home_lane_forbidden",
            IrohAgentMessage("other", "target", "hi", "work-1", 1L, conversationId = "work-1", correlation = correlation) to
                "target_is_work_lane",
        )
        for ((message, reason) in messages) {
            val first = assertIs<AgentSendResult.Accepted>(senderSees(IrohAgentMessage.decode(message.encode())))
            val retry = assertIs<AgentSendResult.Accepted>(senderSees(IrohAgentMessage.decode(message.encode())))
            assertEquals(reason, first.reason)
            assertEquals("duplicate_msg_id", retry.reason)
            assertEquals(message.msgId, first.msgId)
            assertEquals(message.msgId, retry.msgId)
            assertEquals(listOf(message.msgId, message.msgId), observed.takeLast(2).map { it.msgId })
            assertEquals(listOf(correlation, correlation), observed.takeLast(2).map { it.correlation })
        }
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun theSameSenderWithoutALaneStillReachesTheInbox() {
        val result = senderSees(IrohAgentMessage("other", "target", "hi", "m-2", 1L))
        assertIs<AgentSendResult.Delivered>(result)
        assertEquals(listOf("inbox-1"), delivered)
    }
}
