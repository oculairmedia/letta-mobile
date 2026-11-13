package com.letta.mobile.data.chat.sentruns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentRunLedgerTest {
    private val ledger = SentRunLedger()
    private val first = SentMessageId("c1")
    private val second = SentMessageId("c2")
    private val provisional = SentRunId("iroh-run-1")
    private val durable = SentRunId("run-42")

    @Test
    fun aStartedRunIsActiveUntilItEnds() {
        ledger.started(first, setOf(provisional))
        assertEquals(mapOf(first to setOf(provisional)), ledger.state.value.started)
        assertEquals(setOf(provisional), ledger.state.value.active)

        ledger.ended(first, failed = false)
        assertEquals(mapOf(first to setOf(provisional)), ledger.state.value.started)
        assertTrue(ledger.state.value.active.isEmpty())
        assertTrue(ledger.state.value.failed.isEmpty())
    }

    @Test
    fun aPromotedIdJoinsTheSameEntryAndIsActive() {
        ledger.started(first, setOf(provisional))
        ledger.started(first, setOf(durable))
        assertEquals(setOf(provisional, durable), ledger.state.value.started[first])
        assertEquals(setOf(provisional, durable), ledger.state.value.active)
    }

    @Test
    fun aFailedRunPublishesEveryIdAsFailed() {
        ledger.started(first, setOf(provisional, durable))
        ledger.ended(first, failed = true)
        assertEquals(setOf(provisional, durable), ledger.state.value.failed)
        assertTrue(ledger.state.value.active.isEmpty())
    }

    @Test
    fun anIdAddedAfterTheRunEndedNeverBecomesActive() {
        ledger.started(first, setOf(provisional))
        ledger.ended(first, failed = false)
        ledger.started(first, setOf(durable))
        assertEquals(setOf(provisional, durable), ledger.state.value.started[first])
        assertTrue(ledger.state.value.active.isEmpty())
    }

    @Test
    fun endingAnUnknownMessageChangesNothing() {
        ledger.started(first, setOf(provisional))
        ledger.ended(second, failed = true)
        assertEquals(setOf(provisional), ledger.state.value.active)
        assertTrue(ledger.state.value.failed.isEmpty())
    }

    @Test
    fun aLateEchoOfASettledRunIsPublishedAsEnded() {
        ledger.settled(setOf(durable), failed = true)
        ledger.lateEcho(first, durable)
        assertEquals(mapOf(first to setOf(durable)), ledger.state.value.started)
        assertTrue(ledger.state.value.active.isEmpty())
        assertEquals(setOf(durable), ledger.state.value.failed)
    }

    @Test
    fun aLateEchoOfAnUnknownRunPublishesNothing() {
        ledger.settled(setOf(durable), failed = false)
        ledger.lateEcho(first, provisional)
        assertTrue(ledger.state.value.started.isEmpty())
    }

    @Test
    fun onlyTheNewestSettledRunsAreRemembered() {
        ledger.settled(setOf(provisional), failed = false)
        repeat(MAX_SETTLED_RUNS) { ledger.settled(setOf(SentRunId("later-$it")), failed = false) }
        ledger.lateEcho(first, provisional)
        assertTrue(ledger.state.value.started.isEmpty())
    }

    @Test
    fun theLedgerDropsOldEndedEntriesButNeverARunInFlight() {
        ledger.started(first, setOf(provisional))
        repeat(MAX_SENT_MESSAGES + 4) { index ->
            val id = SentMessageId("ended-$index")
            ledger.started(id, setOf(SentRunId("run-$index")))
            ledger.ended(id, failed = true)
        }
        val runs = ledger.state.value
        assertEquals(MAX_SENT_MESSAGES, runs.started.size)
        assertEquals(setOf(provisional), runs.started[first])
        assertEquals(setOf(provisional), runs.active)
        assertTrue(runs.failed.all { failed -> runs.started.values.any { failed in it } })
    }

    @Test
    fun aRowStampedWithASentMessageAsItsTurnJoinsThatMessagesRun() {
        ledger.sent(first)
        ledger.row(turn = "c1", run = "local-run-55")
        ledger.row(turn = "c1", run = "local-run-57")
        assertEquals(setOf(SentRunId("local-run-55"), SentRunId("local-run-57")), ledger.state.value.started[first])
        assertEquals(setOf(SentRunId("local-run-55"), SentRunId("local-run-57")), ledger.state.value.active)
    }

    @Test
    fun aRowOfAnotherTurnPublishesNothing() {
        ledger.sent(first)
        ledger.row(turn = "someone-elses-otid", run = "other-run")
        ledger.row(turn = "iroh-turn-1", run = "other-run")
        ledger.row(turn = null, run = "other-run")
        assertTrue(ledger.state.value.started.isEmpty())
    }

    @Test
    fun settlingAnyRunOfASentMessageEndsIt() {
        ledger.sent(first)
        ledger.sent(second)
        ledger.row(turn = "c1", run = "local-run-55")
        ledger.row(turn = "c1", run = "local-run-57")
        ledger.row(turn = "c2", run = "local-run-58")

        ledger.settled(setOf(SentRunId("local-run-55")), failed = false)

        assertEquals(setOf(SentRunId("local-run-58")), ledger.state.value.active)
        assertTrue(ledger.state.value.failed.isEmpty())
    }
}
