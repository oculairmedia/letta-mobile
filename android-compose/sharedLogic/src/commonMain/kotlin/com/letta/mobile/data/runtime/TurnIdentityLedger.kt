package com.letta.mobile.data.runtime

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The authority's stream-to-stored join (letta-mobile-r1xkl, plan 1.4). When a turn ends the host
 * lists the conversation's newest stored rows once, matches the turn's logical messages to them
 * exactly ([TurnRowMatcher]), and persists `(durable id, message_type) -> (logical id, turn id)`.
 * Every `message.list` row is then served through [enrich], so a client joins the live stream and
 * the stored history on ids the host assigned, never on text or position.
 *
 * Wire shape: an enriched `message.list` row gains two fields and nothing else changes:
 * ```
 * {"id":"ui-msg-9184506","message_type":"assistant_message","content":[...],
 *  "logical_message_id":"ui-msg-9184506","turn_id":"capture-turn1-3ed601a8-..."}
 * ```
 * A row the ledger knows nothing about is served unchanged, with neither field.
 */
class TurnIdentityLedger(
    private val store: TurnIdentityStore,
    private val rows: ConversationRowsSource,
    private val clock: () -> Long,
) {
    private val lock = SynchronizedObject()
    private val conversations = HashMap<String, ConversationIdentities>()
    private val gate = SettleGate(ENRICH_WAIT_MS)

    /** The turn of [conversationId] is about to end: `message.list` waits for [settleTurn] or [abandonSettle]. */
    fun expectSettle(conversationId: String) = gate.expect(conversationId)

    /** The turn ended without a [settleTurn] (failure, cancel): stop holding `message.list` back. */
    fun abandonSettle(conversationId: String) = gate.release(conversationId)

    /** Joins [turn] to the stored rows, persists the map, and reports what matched. Releases the gate. */
    suspend fun settleTurn(conversationId: String, turn: SettledTurn): TurnMatchReport {
        gate.expect(conversationId)
        val startedAt = clock()
        val conversation = conversation(conversationId)
        try {
            return joinWithRetries(conversation, turn).also { report(conversation, turn, it, clock() - startedAt) }
        } finally {
            gate.release(conversationId)
        }
    }

    /** [listed] with `logical_message_id` / `turn_id` added where the ledger knows them. */
    suspend fun enrich(conversationId: String, listed: List<JsonObject>): List<JsonObject> {
        val conversation = conversation(conversationId)
        if (gate.awaitSettled(conversationId)) {
            backfillIfNeeded(conversation, listed)
        } else {
            Telemetry.event(TAG, "turnIdentity.enrichTimedOut", "conversationId" to conversationId)
        }
        val identities = conversation.snapshot()
        return listed.map { row -> row.ref()?.let { identities[it] }?.let { row.withIdentity(it) } ?: row }
    }

    /**
     * Once per conversation: gives rows with no identity a turn id by prompt adjacency, over [orderedRows] oldest first. The request path backfills only the newest [HISTORY_LIMIT]
     * rows; older rows stay unidentified (a client shows them as singles).
     */
    suspend fun backfillHistory(conversationId: String, orderedRows: List<JsonObject>) =
        backfill(conversation(conversationId), orderedRows)

    private suspend fun backfill(conversation: ConversationIdentities, orderedRows: List<JsonObject>) {
        val startedAt = clock()
        val entries = HistoryTurnAssigner.assign(orderedRows, conversation.snapshot().keys)
        if (entries.isNotEmpty()) conversation.persist(entries)
        conversation.markBackfilled()
        Telemetry.event(TAG, "turnIdentity.backfilled", "conversationId" to conversation.conversationId, "rows" to entries.size, durationMs = clock() - startedAt)
    }

    private suspend fun backfillIfNeeded(conversation: ConversationIdentities, listed: List<JsonObject>) {
        val known = conversation.snapshot()
        val unmapped = listed.any { row -> row.ref()?.let { it !in known } == true }
        if (!unmapped || conversation.isBackfilled()) return
        try {
            backfill(conversation, rows.newestRows(conversation.conversationId, HISTORY_LIMIT).reversed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.event(TAG, "turnIdentity.backfillFailed", "conversationId" to conversation.conversationId, "error" to e.toString())
        }
    }

    private suspend fun joinWithRetries(conversation: ConversationIdentities, turn: SettledTurn): TurnMatchReport {
        var remaining = turn.messages
        for (wait in RETRY_WAITS_MS) {
            delay(wait)
            remaining = joinOnce(conversation, SettledTurn(turn.turnId, remaining))
            if (remaining.isEmpty()) break
        }
        return TurnMatchReport(turn.messages.size - remaining.size, remaining.map { it.logicalId })
    }

    private suspend fun joinOnce(conversation: ConversationIdentities, turn: SettledTurn): List<SettledMessage> {
        val newest = rows.newestRows(conversation.conversationId, 2 * turn.messages.size + EXTRA_ROWS).reversed()
        val result = TurnRowMatcher(turn.turnId, newest, settledRows(conversation.snapshot())).match(turn.messages)
        if (result.entries.isNotEmpty()) conversation.persist(result.entries)
        return result.unmatched
    }

    private fun report(conversation: ConversationIdentities, turn: SettledTurn, report: TurnMatchReport, tookMs: Long) {
        val name = if (report.unmatched.isEmpty()) "turnIdentity.settled" else "turnIdentity.unmatched"
        Telemetry.event(
            TAG, name,
            "conversationId" to conversation.conversationId,
            "turnId" to turn.turnId,
            "matched" to report.matched,
            "unmatched" to report.unmatched.joinToString(","),
            durationMs = tookMs,
        )
    }

    private fun conversation(conversationId: String): ConversationIdentities =
        synchronized(lock) { conversations.getOrPut(conversationId) { ConversationIdentities(conversationId, store) } }

    /**
     * Rows a settled turn already claimed. A history-provisional identity may still be replaced, and
     * a user row is re-matched by its `otid`, which yields the same identity.
     */
    private fun settledRows(known: Map<StoredRowRef, RowIdentity>): Set<StoredRowRef> = known.filter { (ref, identity) ->
        ref.messageType != "user_message" && identity.logicalMessageId != HistoryTurnAssigner.provisionalId(ref)
    }.keys

    private companion object {
        const val TAG = "TurnIdentity"
        const val ENRICH_WAIT_MS = 2_000L
        const val HISTORY_LIMIT = 500
        const val EXTRA_ROWS = 8
        val RETRY_WAITS_MS = listOf(0L, 250L, 500L)
    }
}

private fun JsonObject.withIdentity(identity: RowIdentity): JsonObject = JsonObject(
    this + mapOf(
        "logical_message_id" to JsonPrimitive(identity.logicalMessageId),
        "turn_id" to JsonPrimitive(identity.turnId),
    ),
)
