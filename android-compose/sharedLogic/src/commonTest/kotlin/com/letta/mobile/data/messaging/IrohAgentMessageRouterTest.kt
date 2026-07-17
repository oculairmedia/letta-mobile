package com.letta.mobile.data.messaging

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-bn008.3: inbound routing policy — a direct message lands in the
 * most-recent INTERACTIVE conversation and NEVER in an autonomous/heartbeat one,
 * with ping-pong + loop-safety guards.
 */
class IrohAgentMessageRouterTest {

    private fun conv(
        id: String,
        cls: ConversationClass?,
        lastMessageAt: String,
    ) = IrohAgentMessageRouter.ConversationState(
        conversation = Conversation(
            id = ConversationId(id),
            agentId = AgentId("agent-target"),
            conversationClass = cls,
            lastMessageAt = lastMessageAt,
        ),
        busy = false,
    )

    private fun router() = IrohAgentMessageRouter(ownAgentId = "agent-target")

    @Test
    fun landsInMostRecentInteractiveConversation() {
        val decision = router().route(
            fromAgentId = "agent-sender",
            msgId = "m-1",
            candidates = listOf(
                conv("older", ConversationClass.INTERACTIVE, "2026-07-17T10:00:00Z"),
                conv("newest", ConversationClass.INTERACTIVE, "2026-07-17T12:00:00Z"),
            ),
        )
        val deliver = assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(decision)
        assertEquals("newest", deliver.conversationId)
    }

    @Test
    fun neverLandsInAutonomousHeartbeatConversationEvenIfMoreRecent() {
        val decision = router().route(
            fromAgentId = "agent-sender",
            msgId = "m-1",
            candidates = listOf(
                // The heartbeat conversation is the MOST recent, but AUTONOMOUS —
                // must be skipped in favor of the older interactive one.
                conv("heartbeat", ConversationClass.AUTONOMOUS, "2026-07-17T12:00:00Z"),
                conv("interactive", ConversationClass.INTERACTIVE, "2026-07-17T10:00:00Z"),
            ),
        )
        val deliver = assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(decision)
        assertEquals("interactive", deliver.conversationId)
    }

    @Test
    fun createsWhenNoInteractiveConversationExists() {
        val decision = router().route(
            fromAgentId = "agent-sender",
            msgId = "m-1",
            candidates = listOf(conv("only-heartbeat", ConversationClass.AUTONOMOUS, "2026-07-17T12:00:00Z")),
        )
        assertIs<IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver>(decision)
    }

    @Test
    fun untaggedConversationRoutesAsInteractive() {
        val decision = router().route(
            fromAgentId = "agent-sender",
            msgId = "m-1",
            candidates = listOf(conv("legacy", cls = null, "2026-07-17T12:00:00Z")),
        )
        val deliver = assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(decision)
        assertEquals("legacy", deliver.conversationId)
    }

    @Test
    fun queuesWhenChosenInteractiveConversationIsBusy_neverReroutes() {
        val busyInteractive = IrohAgentMessageRouter.ConversationState(
            conversation = Conversation(
                id = ConversationId("busy-newest"),
                agentId = AgentId("agent-target"),
                conversationClass = ConversationClass.INTERACTIVE,
                lastMessageAt = "2026-07-17T12:00:00Z",
            ),
            busy = true,
        )
        val decision = router().route(
            fromAgentId = "agent-sender",
            msgId = "m-1",
            candidates = listOf(
                busyInteractive,
                conv("idle-older", ConversationClass.INTERACTIVE, "2026-07-17T10:00:00Z"),
            ),
        )
        // Must QUEUE on the busy most-recent one — NOT reroute to the idle older one.
        val queue = assertIs<IrohAgentMessageRouter.RoutingDecision.Queue>(decision)
        assertEquals("busy-newest", queue.conversationId)
    }

    @Test
    fun dropsOwnIdentityMessage_pingPongGuard() {
        val decision = IrohAgentMessageRouter(ownAgentId = "agent-target").route(
            fromAgentId = "agent-target", // our own id
            msgId = "m-1",
            candidates = listOf(conv("c", ConversationClass.INTERACTIVE, "2026-07-17T12:00:00Z")),
        )
        val dropped = assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(decision)
        assertEquals("ping_pong_own_or_sibling", dropped.reason)
    }

    @Test
    fun dropsSiblingIdentityMessage() {
        val r = IrohAgentMessageRouter(ownAgentId = "agent-target", siblingAgentIds = setOf("agent-sibling"))
        val decision = r.route("agent-sibling", "m-1", listOf(conv("c", ConversationClass.INTERACTIVE, "t")))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(decision)
    }

    @Test
    fun dropsDuplicateMsgId_atMostOncePerInbound() {
        val r = router()
        val candidates = listOf(conv("c", ConversationClass.INTERACTIVE, "2026-07-17T12:00:00Z"))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(r.route("agent-sender", "dup", candidates))
        // Redelivery of the same msgId is dropped exactly-once.
        val second = assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(r.route("agent-sender", "dup", candidates))
        assertEquals("duplicate_msg_id", second.reason)
    }

    @Test
    fun perSenderCapDropsAfterLimit() {
        val r = IrohAgentMessageRouter(ownAgentId = "agent-target", maxPerSender = 2)
        val candidates = listOf(conv("c", ConversationClass.INTERACTIVE, "2026-07-17T12:00:00Z"))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(r.route("s", "m-1", candidates))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(r.route("s", "m-2", candidates))
        val capped = assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(r.route("s", "m-3", candidates))
        assertEquals("per_sender_cap_exceeded", capped.reason)
    }
}
