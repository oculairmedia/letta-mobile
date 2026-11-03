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
            router.route("other", "one", states, IrohAgentMessageRouter.InboundAddress(lane = "home")),
        ).reason)
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(
            router.route("other", "two", states, IrohAgentMessageRouter.InboundAddress(lane = "inbox")),
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
                router.route("sender", lane, states, IrohAgentMessageRouter.InboundAddress(lane = lane)),
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
            router.route("sender", "one", states, IrohAgentMessageRouter.InboundAddress(targetConversationId = "plain", lane = "home")),
        ).conversationId)
        assertEquals("target_conversation_not_found", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
            router.route("sender", "two", states, IrohAgentMessageRouter.InboundAddress(targetConversationId = "missing")),
        ).reason)
    }

    @Test
    fun explicitBusyTargetQueuesEvenWhenInboxIsIdle() {
        val result = IrohAgentMessageRouter("target").route(
            "sender", "m", listOf(state("plain", busy = true), state("inbox", "lane:inbox")),
            IrohAgentMessageRouter.InboundAddress(targetConversationId = "plain"),
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
                router.route("sender", "w$n", states, IrohAgentMessageRouter.InboundAddress(targetConversationId = id)),
            ).reason)
        }
        assertEquals("home_lane_forbidden", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(
            router.route("sender", "h", states, IrohAgentMessageRouter.InboundAddress(targetConversationId = "home")),
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
    @Test
    fun dropsOwnIdentityMessage_pingPongGuard() {
        val result = IrohAgentMessageRouter("target").route("target", "m", listOf(state("inbox", "lane:inbox")))
        assertEquals("ping_pong_own_or_sibling", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result).reason)
    }

    @Test
    fun dropsSiblingIdentityMessage() {
        val result = IrohAgentMessageRouter("target", setOf("sibling")).route("sibling", "m", emptyList())
        assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result)
    }

    @Test
    fun dropsDuplicateMsgId_atMostOncePerInbound() {
        val router = IrohAgentMessageRouter("target")
        val states = listOf(state("inbox", "lane:inbox"))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(router.route("sender", "m", states))
        val result = assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(router.route("sender", "m", states))
        assertEquals("duplicate_msg_id", result.reason)
    }

    @Test
    fun perSenderCapDropsAfterLimit() {
        val router = IrohAgentMessageRouter("target", maxPerSender = 2)
        val states = listOf(state("inbox", "lane:inbox"))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(router.route("sender", "1", states))
        assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(router.route("sender", "2", states))
        val result = assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(router.route("sender", "3", states))
        assertEquals("per_sender_cap_exceeded", result.reason)
    }

    @Test
    fun pingPongGuardFiresBeforeExplicitTarget() {
        val result = IrohAgentMessageRouter("target").route(
            "target", "m", listOf(state("plain")), IrohAgentMessageRouter.InboundAddress(targetConversationId = "plain"),
        )
        assertEquals("ping_pong_own_or_sibling", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result).reason)
    }

    @Test
    fun explicitTargetAbsentDropsWithDistinctReasonNeverCreates() {
        val result = IrohAgentMessageRouter("target").route(
            "sender", "m", listOf(state("inbox", "lane:inbox")),
            IrohAgentMessageRouter.InboundAddress(targetConversationId = "missing"),
        )
        assertEquals("target_conversation_not_found", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result).reason)
    }

    @Test
    fun explicitTargetPresentAndBusyQueuesOnThatConversation() {
        val result = IrohAgentMessageRouter("target").route(
            "sender", "m", listOf(state("plain", busy = true), state("inbox", "lane:inbox")),
            IrohAgentMessageRouter.InboundAddress(targetConversationId = "plain"),
        )
        assertEquals("plain", assertIs<IrohAgentMessageRouter.RoutingDecision.Queue>(result).conversationId)
    }

    @Test
    fun explicitTargetPresentAndIdleDeliversToThatConversationNotMostRecent() {
        val result = IrohAgentMessageRouter("target").route(
            "sender", "m", listOf(state("inbox", "lane:inbox"), state("plain")),
            IrohAgentMessageRouter.InboundAddress(targetConversationId = "plain"),
        )
        val id = assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(result).conversationId
        assertEquals("plain", id)
        kotlin.test.assertNotEquals("inbox", id)
    }

    @Test
    fun untaggedConversationRoutesAsInteractive() {
        val router = IrohAgentMessageRouter("target")
        val states = listOf(state("plain"))
        assertIs<IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver>(router.route("sender", "1", states))
        val explicit = router.route("sender", "2", states, IrohAgentMessageRouter.InboundAddress(targetConversationId = "plain"))
        assertEquals("plain", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(explicit).conversationId)
    }

    @Test
    fun createsWhenNoInteractiveConversationExists() {
        val result = IrohAgentMessageRouter("target").route("sender", "m", listOf(state("home", "lane:home")))
        assertIs<IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver>(result)
    }

    @Test
    fun queuesWhenChosenInteractiveConversationIsBusy_neverReroutes() {
        val result = IrohAgentMessageRouter("target").route(
            "sender", "m", listOf(state("home", "lane:home"), state("inbox", "lane:inbox", busy = true)),
        )
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Queue>(result).conversationId)
    }

    @Test
    fun neverLandsInAutonomousHeartbeatConversationEvenIfMoreRecent() {
        val newer = Conversation(ConversationId("heartbeat"), AgentId("target"),
            conversationClass = com.letta.mobile.data.model.ConversationClass.AUTONOMOUS, lastMessageAt = "2026-01-01")
        val inbox = Conversation(ConversationId("inbox"), AgentId("target"), lastMessageAt = "2020-01-01", tags = listOf("lane:inbox"))
        val result = IrohAgentMessageRouter("target").route("sender", "m", listOf(newer, inbox).map {
            IrohAgentMessageRouter.ConversationState(it, false)
        })
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(result).conversationId)
    }

    @Test
    fun nullExplicitTargetRoutesToInboxDespiteNewerInteractiveConversation() {
        val newer = Conversation(ConversationId("newer"), AgentId("target"), lastMessageAt = "2026-01-01")
        val inbox = Conversation(ConversationId("inbox"), AgentId("target"), lastMessageAt = "2020-01-01", tags = listOf("lane:inbox"))
        val states = listOf(newer, inbox).map { IrohAgentMessageRouter.ConversationState(it, false) }
        val result = IrohAgentMessageRouter("target").route("sender", "m", states, IrohAgentMessageRouter.InboundAddress())
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(result).conversationId)
    }

    @Test
    fun defaultRoutingUsesInboxDespiteNewerInteractiveConversation() {
        val newer = Conversation(ConversationId("newer"), AgentId("target"), lastMessageAt = "2026-01-01")
        val inbox = Conversation(ConversationId("inbox"), AgentId("target"), lastMessageAt = "2020-01-01", tags = listOf("lane:inbox"))
        val result = IrohAgentMessageRouter("target").route("sender", "m", listOf(newer, inbox).map {
            IrohAgentMessageRouter.ConversationState(it, false)
        })
        assertEquals("inbox", assertIs<IrohAgentMessageRouter.RoutingDecision.Deliver>(result).conversationId)
    }

    @Test
    fun inboxAlsoTaggedWorkIsDroppedBeforeBusy() {
        val conv = Conversation(ConversationId("bad"), AgentId("target"), tags = listOf("lane:inbox", "lane:work"))
        val result = IrohAgentMessageRouter("target").route("sender", "m", listOf(IrohAgentMessageRouter.ConversationState(conv, true)))
        assertEquals("target_is_work_lane", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result).reason)
    }

    @Test
    fun inboxAlsoTaggedHomeIsDroppedBeforeBusy() {
        val conv = Conversation(ConversationId("bad"), AgentId("target"), tags = listOf("lane:inbox", "lane:home"))
        val result = IrohAgentMessageRouter("target").route("sender", "m", listOf(IrohAgentMessageRouter.ConversationState(conv, true)))
        assertEquals("home_lane_forbidden", assertIs<IrohAgentMessageRouter.RoutingDecision.Dropped>(result).reason)
    }

}
