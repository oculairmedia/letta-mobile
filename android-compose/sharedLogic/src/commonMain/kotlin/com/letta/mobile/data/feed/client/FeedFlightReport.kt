package com.letta.mobile.data.feed.client

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr
import com.letta.mobile.util.flight.LatestReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What a [FeedTimelineSource] holds when it reports: its cursor, frames applied and rows drawn. */
internal class FeedPosition(val cursor: FeedCursor?, val framesApplied: Long, val rows: Int)

/**
 * What one conversation's feed client tells telemetry, and so the flight record: the position it applied, the pages it read,
 * its resyncs, the turns it saw open and close, and pushes it could not read. Ids, seqs, counts and states only. A pushed
 * frame's position reports through a [LatestReporter] (once per [CURSOR_REPORT_MS] while frames keep arriving, ending on the
 * last), so a streaming turn writes a line per window, not one per frame; a page and a resync report at once.
 */
internal class FeedFlightReport(
    scope: CoroutineScope,
    private val conversation: ConversationId,
    private val position: () -> FeedPosition,
) {
    private val appliedReport = LatestReporter<FeedCursor>(scope, CURSOR_REPORT_MS) { report("applied") }

    /** A pushed frame was applied and moved the cursor to [cursor]. */
    fun applied(cursor: FeedCursor) = appliedReport.offer(cursor)

    /** A resync from the cursor starts, for [reason]. */
    fun resync(reason: FeedResync) = report("resync", "reason" attr reason.name)

    /** A `feed.subscribe` [page] was applied. */
    fun page(page: FeedPage) = report(
        "read.page", "mode" attr page.mode, "resyncReason" attr page.resyncReason, "entries" attr page.entries.size,
        "headSeq" attr page.headSeq, "hasMore" attr page.hasMore,
    )

    /** [entry] is being applied: a turn opening or closing is reported; stream deltas between them report nothing. */
    fun entry(entry: FeedDelta) {
        val name = when (entry.kind) {
            ConversationFeedWire.KIND_TURN_OPEN -> "turn.opened"
            ConversationFeedWire.KIND_TURN_CLOSED -> "turn.closed"
            else -> return
        }
        val observed = entry.payload?.get(TERMINAL_OBSERVED)?.let { (it as? JsonPrimitive)?.booleanOrNull }
        Telemetry.event(
            TAG, name, "conversationId" attr conversation.value, "turnId" attr entry.turn, "seq" attr entry.seq,
            "terminalObserved" attr observed,
        )
    }

    /** A pushed frame could not be decoded; it was dropped. */
    fun undecodable(failure: Throwable) {
        Telemetry.event(TAG, "frame.undecodable", "class" attr failure::class.simpleName, level = Telemetry.Level.WARN)
    }

    private fun report(name: String, vararg extra: Pair<String, TelemetryValue>) {
        val now = position()
        Telemetry.event(
            TAG, name, "conversationId" attr conversation.value, "epoch" attr now.cursor?.epoch, "seq" attr now.cursor?.seq,
            "framesApplied" attr now.framesApplied, "rows" attr now.rows, *extra,
        )
    }

    private companion object {
        const val TAG = "FeedTimeline"

        /** Longest an applied cursor goes unreported while frames keep arriving. */
        const val CURSOR_REPORT_MS = 5_000L

        /** The `turn_closed` payload field saying whether a real terminal went through the feed first. */
        const val TERMINAL_OBSERVED = "terminal_observed"
    }
}
