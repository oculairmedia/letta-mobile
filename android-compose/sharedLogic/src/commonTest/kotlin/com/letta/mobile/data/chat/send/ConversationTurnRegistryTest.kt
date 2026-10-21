package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationTurnRegistryTest {
    private val registry = ConversationTurnRegistry { ConversationId("visible") }

    @Test
    fun aliasBindingRetainsOriginatingStateWithoutAdoptingAnotherPendingSend() {
        val origin = awaiting("local", "otid")
        val other = awaiting("other", "other-otid")
        val binding = registry.bindInboundTurnByOtid(
            QueuedSendId("otid"),
            ConversationId("server"),
            TurnTail("turn", "run"),
        )

        assertSame(origin, binding.state)
        assertNull(binding.rekeyedConversationId)
        assertSame(origin, registry.stateFor(ConversationId("server")))
        assertSame(other, registry.peekState(ConversationId("other")))
        assertEquals("local", registry.resolveConversationId(ConversationId("server"))?.value)
        assertNull(other.turnId)
    }

    @Test
    fun startedBeforeEchoAbsorbsOrphanAndReportsUiPublicationOutsideRegistry() {
        val origin = awaiting("local", "otid")
        val orphan = registry.resolveStateForTurnStarted(started("server", "turn", "run"))
        orphan.turnId = "turn"
        orphan.runId = "run"
        orphan.stopReason = "end_turn"
        orphan.usageRecorded = true
        orphan.deliveredAssistantContent = true

        val binding = registry.bindInboundTurnByOtid(
            QueuedSendId("otid"),
            ConversationId("server"),
            TurnTail("turn", "run"),
        )

        assertSame(origin, binding.state)
        assertEquals(ConversationId("local"), binding.rekeyedConversationId)
        assertEquals("end_turn", origin.stopReason)
        assertTrue(origin.usageRecorded)
        assertTrue(origin.deliveredAssistantContent)
        assertEquals("turn", origin.identity.active?.turnId)
        assertEquals(1, registry.trackedConversationCount)
    }

    @Test
    fun settledOtidCannotCreateAnAliasOrResurrectATerminalState() {
        val origin = awaiting("local", "otid")
        registry.retainSettledOtid(QueuedSendId("otid"))
        assertNull(
            registry.bindInboundTurnByOtid(
                QueuedSendId("otid"),
                ConversationId("server"),
                TurnTail("turn", "run"),
            ).state,
        )
        assertEquals("server", registry.resolveConversationId(ConversationId("server"))?.value)
        origin.reachedTerminal = true
        assertNull(
            registry.bindInboundTurnByOtid(
                QueuedSendId("otid"),
                ConversationId("server"),
                TurnTail("turn", "run"),
            ).state,
        )
    }

    @Test
    fun terminalStateRejectsUnfencedOtid() {
        awaiting("local", "otid").reachedTerminal = true
        assertNull(
            registry.bindInboundTurnByOtid(
                QueuedSendId("otid"),
                ConversationId("server"),
                TurnTail("turn", "run"),
            ).state,
        )
    }

    @Test
    fun aliasRetentionEvictsOldestAtSixtyFourAndRefreshesReboundAlias() {
        awaiting("local", "otid")
        repeat(64) {
            registry.bindInboundTurnByOtid(
                QueuedSendId("otid"),
                ConversationId("server-$it"),
                TurnTail(null, null),
            )
        }
        registry.bindInboundTurnByOtid(QueuedSendId("otid"), ConversationId("server-0"), TurnTail(null, null))
        registry.bindInboundTurnByOtid(QueuedSendId("otid"), ConversationId("server-64"), TurnTail(null, null))

        assertEquals("local", registry.resolveConversationId(ConversationId("server-0"))?.value)
        assertEquals("server-1", registry.resolveConversationId(ConversationId("server-1"))?.value)
        assertEquals("local", registry.resolveConversationId(ConversationId("server-64"))?.value)
    }

    @Test
    fun settledOtidRetentionKeepsSixteenAndDoesNotRefreshDuplicate() {
        val origin = awaiting("local", "otid-0")
        repeat(16) { registry.retainSettledOtid(QueuedSendId("otid-$it")) }
        registry.retainSettledOtid(QueuedSendId("otid-0"))
        registry.retainSettledOtid(QueuedSendId("otid-16"))

        assertSame(
            origin,
            registry.bindInboundTurnByOtid(
                QueuedSendId("otid-0"),
                ConversationId("server-0"),
                TurnTail(null, null),
            ).state,
        )
        origin.otid = "otid-1"
        assertNull(
            registry.bindInboundTurnByOtid(
                QueuedSendId("otid-1"),
                ConversationId("server-1"),
                TurnTail(null, null),
            ).state,
        )
    }

    @Test
    fun revisitingSettledStateRefreshesEvictionRecencyButPeekDoesNot() {
        repeat(32) { registry.stateFor(ConversationId("conv-$it")) }
        val revisited = registry.stateFor(ConversationId("conv-0"))
        registry.peekState(ConversationId("conv-1"))
        registry.stateFor(ConversationId("conv-32"))

        assertSame(revisited, registry.peekState(ConversationId("conv-0")))
        assertNull(registry.peekState(ConversationId("conv-1")))
        assertEquals(32, registry.trackedConversationCount)
    }

    @Test
    fun liveOwnershipSurvivesSoftCapOverflow() {
        repeat(34) { awaiting("conv-$it", "otid-$it") }
        assertEquals(34, registry.trackedConversationCount)
        assertEquals("otid-0", registry.peekState(ConversationId("conv-0"))?.otid)
    }

    @Test
    fun retiredTurnFenceAndRunFenceSurviveStateClearAndDoNotStealAwaitingSend() {
        val retired = registry.stateFor(ConversationId("retired"))
        retired.identity.turnStarted("retired", "old-turn", "old-run")
        retired.turnId = "old-turn"
        retired.runId = "old-run"
        retired.retainSettledRun()
        retired.identity.clear()
        retired.turnId = null
        retired.runId = null
        awaiting("new", "new-otid")

        assertSame(retired, registry.resolveStateByTurnId("old-turn"))
        assertTrue(retired.identity.isFenced("old-turn"))
        assertNull(registry.resolveStateByRunId("old-run"))
        assertNull(registry.peekState(ConversationId("new"))?.turnId)
    }

    @Test
    fun exactLiveIdentityOutranksAnotherConversationsRetiredFence() {
        val history = registry.stateFor(ConversationId("history"))
        history.identity.turnStarted("history", "shared-turn", "shared-run")
        history.identity.clear()
        history.settledRunIds += "shared-run"
        val live = awaiting("live", "live-otid")
        live.turnId = "shared-turn"
        live.runId = "shared-run"

        assertSame(live, registry.resolveStateByTurnId("shared-turn"))
        assertSame(live, registry.resolveStateByRunId("shared-run"))
    }

    @Test
    fun ambiguousAwaitingSendsWithoutRecentOwnerDoNotAdoptForeignTerminal() {
        awaiting("first", "first-otid")
        awaiting("second", "second-otid")
        assertNull(registry.resolveStateByTurnId("unknown-turn"))
        assertNull(registry.resolveStateByRunId("unknown-run"))
        assertNull(registry.fallbackState())
    }

    @Test
    fun unknownPassiveTerminalMaterializesVisibleFallbackOnlyWhenRegistryIsEmpty() {
        val fallback = registry.resolveStateByTurnId("unknown-turn")
        assertEquals("visible", fallback?.conversationId)
        assertFalse(fallback!!.isTracking)
        registry.stateFor(ConversationId("other"))
        assertNull(registry.fallbackState())
        registry.lastActiveConversationId = "other"
        assertSame(registry.peekState(ConversationId("other")), registry.fallbackState())
    }

    @Test
    fun remoteTurnStartedDoesNotAdoptAwaitingSendWithoutOtid() {
        val pending = awaiting("local", "otid")
        val remote = registry.resolveStateForTurnStarted(started("remote", "turn", "run"))
        assertEquals("remote", remote.conversationId)
        assertNull(pending.turnId)
        assertEquals(2, registry.trackedConversationCount)
    }

    private fun awaiting(conversationId: String, otid: String): ConversationTurnState =
        registry.stateFor(ConversationId(conversationId)).also {
            it.localConversationId = conversationId
            it.otid = otid
            it.identity.acceptedSend(conversationId)
        }

    private fun started(conversationId: String, turnId: String, runId: String) =
        WsTimelineEvent.TurnStarted(turnId = turnId, agentId = "agent", runId = runId, conversationId = conversationId)
}
