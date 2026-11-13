package com.letta.mobile.data.chat.send

import com.letta.mobile.data.chat.sentruns.SentMessageId
import com.letta.mobile.data.chat.sentruns.SentRunId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run each own send started, published by identity from the turn state's own transitions. */
class ConversationTurnStateSentRunsTest {
    private val registry = ConversationTurnRegistry { ConversationId(CONVERSATION) }
    private val state = registry.stateFor(ConversationId(CONVERSATION))
    private val runs get() = registry.sentRuns.state.value

    private fun accept(otid: String) {
        val pending = QueuedChatSend(QueuedSendId(otid), QueueConversationId(CONVERSATION), "hello")
        state.acceptSend(pending, send = { true }, onAccepted = {})
    }

    private fun started(turn: String, run: String) = WsTimelineEvent.TurnStarted(turn, AGENT, CONVERSATION, run)

    /** What the projection does with a TurnStarted frame. */
    private fun turnStarted(turn: String, run: String) {
        val event = started(turn, run)
        if (!state.advanceStartedIdentity(event)) state.recordStarted(event)
    }

    private fun echo(otid: String, turn: String?, run: String?) {
        registry.bindInboundTurnByOtid(QueuedSendId(otid), null, TurnTail(turn, run))
    }

    @Test
    fun nothingIsPublishedUntilTheEchoPairsTheSendWithItsRun() {
        accept("c1")
        turnStarted("t1", "iroh-run-1")
        assertTrue(runs.started.isEmpty())

        echo("c1", "t1", "iroh-run-1")
        assertEquals(mapOf(SentMessageId("c1") to setOf(SentRunId("iroh-run-1"))), runs.started)
        assertEquals(setOf(SentRunId("iroh-run-1")), runs.active)
    }

    @Test
    fun theEchoAlsoPublishesTheSameTurnsCurrentRunId() {
        accept("c1")
        turnStarted("t1", "iroh-run-1")
        echo("c1", "t1", "run-42")
        assertEquals(setOf(SentRunId("iroh-run-1"), SentRunId("run-42")), runs.started[SentMessageId("c1")])
    }

    @Test
    fun aDurableIdPromotingTheProvisionalOneJoinsTheEntry() {
        accept("c1")
        turnStarted("t1", "iroh-run-1")
        echo("c1", "t1", "iroh-run-1")
        turnStarted("t1", "run-42")
        assertEquals(setOf(SentRunId("iroh-run-1"), SentRunId("run-42")), runs.started[SentMessageId("c1")])
        assertEquals(setOf(SentRunId("iroh-run-1"), SentRunId("run-42")), runs.active)
    }

    @Test
    fun anotherTurnsRunIsNeverAddedToTheSend() {
        accept("c1")
        turnStarted("t0", "someone-else")
        echo("c1", "t1", "run-1")
        assertEquals(setOf(SentRunId("run-1")), runs.started[SentMessageId("c1")])
    }

    @Test
    fun anEchoForAnotherOtidBindsNothing() {
        accept("c1")
        echo("x9", "t1", "run-1")
        assertTrue(runs.started.isEmpty())
    }

    @Test
    fun theTurnBeingReplacedEndsTheRun() {
        accept("c1")
        turnStarted("t1", "run-1")
        echo("c1", "t1", "run-1")
        turnStarted("t2", "run-2")
        assertEquals(setOf(SentRunId("run-1")), runs.started[SentMessageId("c1")])
        assertTrue(runs.active.isEmpty())
    }

    @Test
    fun clearingTheTurnEndsTheRunAndAFailureMarksIt() {
        accept("c1")
        echo("c1", "t1", "run-1")
        state.ownRun.end(failed = true)
        TurnSettlementOwnership(registry, ChatSendQueue()).clear(state, reason = "test")
        assertTrue(runs.active.isEmpty())
        assertEquals(setOf(SentRunId("run-1")), runs.failed)

        accept("c2")
        echo("c2", "t2", "run-2")
        TurnSettlementOwnership(registry, ChatSendQueue()).clear(state, reason = "test")
        assertTrue(runs.active.isEmpty())
        assertEquals(setOf(SentRunId("run-1")), runs.failed)
    }

    @Test
    fun anEchoAfterTheTurnSettledPairsTheSettledRunAsEnded() {
        accept("c1")
        turnStarted("t1", "run-1")
        state.ownRun.settle(setOf("run-1", null), failed = false)
        TurnSettlementOwnership(registry, ChatSendQueue()).clear(state, reason = "test")
        assertTrue(runs.started.isEmpty())

        echo("c1", "t1", "run-1")
        assertEquals(mapOf(SentMessageId("c1") to setOf(SentRunId("run-1"))), runs.started)
        assertTrue(runs.active.isEmpty())
    }

    @Test
    fun aNewSendEndsAnOwnRunStillMarkedInFlight() {
        accept("c1")
        echo("c1", "t1", "run-1")
        accept("c2")
        assertTrue(runs.active.isEmpty())
    }

    private companion object {
        const val CONVERSATION = "conv-1"
        const val AGENT = "agent-1"
    }
}
