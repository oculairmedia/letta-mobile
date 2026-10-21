package com.letta.mobile.data.chat.send

import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class TurnTerminalPolicyTest {
    @Test
    fun completionAndCleanupFollowOnlyObservedEvidence() {
        val empty = TurnTerminalEvidence(false, null, null, null)
        val delivered = empty.copy(deliveredAssistantContent = true)
        val partial = delivered.copy(bufferedErrorMessage = "unknown provider failure")
        val completed = partial.copy(stopReason = "end_turn")
        val rows = listOf(
            Case(empty, BridgeTurnStatus.Completed, Expected(false, false, false)),
            Case(empty, BridgeTurnStatus.Failed, Expected(true, true, true)),
            Case(delivered, BridgeTurnStatus.Failed, Expected(false, false, false)),
            Case(partial, BridgeTurnStatus.Failed, Expected(true, true, true)),
            Case(completed, BridgeTurnStatus.Failed, Expected(false, false, false)),
            Case(empty, BridgeTurnStatus.Cancelled, Expected(true, false, true)),
            Case(delivered, BridgeTurnStatus.Cancelled, Expected(true, false, true)),
            Case(empty, BridgeTurnStatus.Unknown("paused"), Expected(false, false, false)),
        )
        val cancelled = TurnFailureNotice("cancelled", TurnFailureNotices.CANCELLED_MESSAGE)
        rows.forEach { row ->
            val request = request(row.status)
            val outcome = TurnTerminalPolicy.decide(row.evidence, request, cancelled)
            assertEquals(row.expected, Expected(outcome.notice != null, outcome.deadTurn, outcome.cleanupFragments), "$row")
        }
        val synthetic = request(BridgeTurnStatus.Failed).copy(
            event = WsTimelineEvent.TurnDone("turn", "iroh-run-synthetic", BridgeTurnStatus.Failed),
        )
        assertEquals(Expected(false, false, true), TurnTerminalPolicy.decide(delivered, synthetic, null).let {
            Expected(it.notice != null, it.deadTurn, it.cleanupFragments)
        })
    }

    @Test
    fun cancelledNoticeIsSuppliedByExistingQueuePolicy() {
        val evidence = TurnTerminalEvidence(false, null, null, null)
        val supplied = TurnFailureNotice("cancelled", "Queued response cancelled")
        val outcome = TurnTerminalPolicy.decide(evidence, request(BridgeTurnStatus.Cancelled), supplied)
        assertSame(supplied, outcome.notice)
        assertFalse(outcome.deadTurn)
        assertEquals("already visible", TurnTerminalPolicy.nextError(evidence, BridgeTurnStatus.Cancelled, supplied, "already visible"))
    }

    @Test
    fun errorSelectionPreservesDeliveredFailureAndUsesBufferedErrorsFirst() {
        val empty = TurnTerminalEvidence(false, null, null, null)
        val notice = TurnFailureNotice("unknown", "fixed notice")
        assertEquals("fixed notice", TurnTerminalPolicy.nextError(empty, BridgeTurnStatus.Failed, notice, "existing"))
        assertEquals("existing", TurnTerminalPolicy.nextError(empty, BridgeTurnStatus.Failed, null, "existing"))
        assertEquals("raw reason", TurnTerminalPolicy.nextError(
            empty.copy(bufferedErrorMessage = "raw reason"), BridgeTurnStatus.Failed, notice, "existing",
        ))
        assertEquals("raw reason", TurnTerminalPolicy.nextError(
            empty.copy(bufferedErrorMessage = "raw reason"), BridgeTurnStatus.Completed, null, "existing",
        ))
        assertEquals("Turn ended unexpectedly (paused)", TurnTerminalPolicy.nextError(
            empty, BridgeTurnStatus.Unknown("paused"), null, null,
        ))
        val errorStop = empty.copy(stopReason = "ERROR")
        val fallback = "Agent run failed after your message was sent. No error details were provided by the shim."
        assertEquals(fallback, TurnTerminalPolicy.nextError(errorStop, BridgeTurnStatus.Completed, null, "existing"))
        assertEquals(fallback, TurnTerminalPolicy.nextError(errorStop, BridgeTurnStatus.Unknown("paused"), null, "existing"))
    }

    private fun request(status: BridgeTurnStatus) = TurnSettlementRequest(
        WsTimelineEvent.TurnDone("turn", "run", status), "test",
    )

    private data class Case(val evidence: TurnTerminalEvidence, val status: BridgeTurnStatus, val expected: Expected)
    private data class Expected(val notice: Boolean, val failedLocal: Boolean, val cleanup: Boolean)
}
