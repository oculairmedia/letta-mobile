package com.letta.mobile.data.messaging

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IrohAgentMessageRouterTest {
    private fun state(id: String, tag: String? = null, busy: Boolean = false) =
        IrohAgentMessageRouter.ConversationState(
            Conversation(ConversationId(id), AgentId("target"), tags = listOfNotNull(tag)), busy,
        )

    @Test
    fun firstMessageCreatesInboxAndSecondUsesSameInbox() {
        val router = IrohAgentMessageRouter("target")
        val states = mutableListOf(state("home", "lane:home"))
        val deliveries = mutableMapOf<String, MutableList<String>>()
        var creations = 0
        fun send(id: String) {
            val decision = router.route("sender", id, states)
            val destination = when (decision) {
                IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver -> {
                    creations++
                    states += state("created-inbox", "lane:inbox")
                    "created-inbox"
                }
                is IrohAgentMessageRouter.RoutingDecision.Deliver -> decision.conversationId
                else -> error("unexpected $decision")
            }
            deliveries.getOrPut(destination) { mutableListOf() }.add(id)
        }
        send("first")
        send("second")
        assertEquals(1, creations)
        assertEquals(listOf("first", "second"), deliveries["created-inbox"]?.toList())
        assertEquals(null, deliveries["home"])
    }

    @Test
    fun busyHomeIsUntouchedAndInboxReceives() {
        val states = listOf(state("home", "lane:home", busy = true), state("inbox", "lane:inbox"))
        val delivery = assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(
            IrohAgentMessageRouter("target").route("sender", "m", states),
        )
        val received = mutableMapOf(delivery.conversationId to listOf("m"))
        assertEquals(listOf("m"), received["inbox"])
        assertEquals(null, received["home"])
    }

    @Test
    fun nonOwnerCannotSendToHomeButCanSendToInbox() {
        val router = IrohAgentMessageRouter("target")
        val states = listOf(state("home", "lane:home"), state("inbox", "lane:inbox"))
        assertEquals("home_lane_forbidden", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
            router.route("other", "one", states, lane = "home"),
        ).reason)
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(
            router.route("other", "two", states, lane = "inbox"),
        ).conversationId)
    }

    @Test
    fun duplicateLaneTagsAreRejectedInsteadOfChoosingByListOrder() {
        val states = listOf(state("newest", "lane:inbox"), state("older", "lane:inbox"))
        val result = IrohAgentMessageRouter("target").route("sender", "m", states)
        assertEquals("duplicate_lane_tags", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result).reason)
    }

    @Test
    fun workAndUnknownLanesAreNotInboundTargets() {
        val router = IrohAgentMessageRouter("target")
        val states = listOf(state("work", "lane:work"), state("inbox", "lane:inbox"))
        for (lane in listOf("work", "other")) {
            assertEquals("unsupported_lane", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
                router.route("sender", lane, states, lane = lane),
            ).reason)
        }
    }

    @Test
    fun busyInboxQueuesWithoutRerouting() {
        val result = IrohAgentMessageRouter("target").route("sender", "m", listOf(
            state("inbox", "lane:inbox", busy = true), state("home", "lane:home"),
        ))
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Queue>(result).conversationId)
    }

    @Test
    fun explicitIdWinsAndMissingIdDrops() {
        val router = IrohAgentMessageRouter("target")
        val states = listOf(state("work", "lane:work"), state("inbox", "lane:inbox"), state("plain"))
        // conversation_id wins over lane: lane:"home" is ignored when an id is present.
        assertEquals("plain", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(
            router.route("sender", "one", states, targetConversationId = "plain", lane = "home"),
        ).conversationId)
        assertEquals("target_conversation_not_found", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
            router.route("sender", "two", states, targetConversationId = "missing"),
        ).reason)
    }

    @Test
    fun explicitBusyTargetQueuesEvenWhenInboxIsIdle() {
        val result = IrohAgentMessageRouter("target").route(
            "sender", "m", listOf(state("plain", busy = true), state("inbox", "lane:inbox")),
            targetConversationId = "plain",
        )
        assertEquals("plain", assertIs<IrohAgentMessageRouter.RoutingDecision.Queue>(result).conversationId)
    }

    @Test
    fun explicitIdCannotBeABackDoorIntoWorkOrHome() {
        val router = IrohAgentMessageRouter("target")
        val states = listOf(
            state("work", "lane:work"), state("busyWork", "lane:work", busy = true), state("home", "lane:home"),
        )
        for ((n, id) in listOf("work", "busyWork").withIndex()) {
            assertEquals("target_is_work_lane", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
                router.route("sender", "w$n", states, targetConversationId = id),
            ).reason)
        }
        assertEquals("home_lane_forbidden", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
            router.route("sender", "h", states, targetConversationId = "home"),
        ).reason)
    }

    @Test
    fun guardPoliciesRemainInForce() {
        val router = IrohAgentMessageRouter("target", setOf("sibling"), maxPerSender = 1)
        val states = listOf(state("inbox", "lane:inbox"))
        assertEquals(
            "ping_pong_own_or_sibling",
            assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(router.route("target", "a", states)).reason,
        )
        assertEquals(
            "ping_pong_own_or_sibling",
            assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(router.route("sibling", "b", states)).reason,
        )
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(router.route("other", "c", states))
        assertEquals(
            "duplicate_msg_id",
            assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(router.route("other", "c", states)).reason,
        )
        assertEquals(
            "per_sender_cap_exceeded",
            assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(router.route("other", "d", states)).reason,
        )
    }
}
