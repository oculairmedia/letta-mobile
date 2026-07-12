package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-j98r5.1: the Iroh OBSERVER path stamps a live assistant row with
 * `runId = "iroh-observer-run-<conversationId>"` (IrohChannelTransport.kt:414).
 * The synthetic-row-replacement machinery in the reconciler
 * ([canReplaceIrohSyntheticLiveRow]) only recognized the `"iroh-run-"` prefix,
 * so an `"iroh-observer-run-"` id was misclassified as a REAL run id. That
 * defeated the live→reconciled collapse: [mergeServerMessages] appended the
 * reconciled final as a SECOND Confirmed row → desktop showed a DUPLICATE.
 *
 * These tests reproduce the duplicate at the reconcile seam. They FAIL on
 * unmodified main (2 assistant rows) and PASS once the observer run id is
 * classified as synthetic.
 *
 * CLASSIFICATION ONLY: no transport/ingest/ordering behavior is exercised or
 * changed — the observer stamp value is left as-is and only its downstream
 * classification is corrected.
 */
class IrohObserverRunIdReconcileDedupTest {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    /**
     * The App Server reconcile returns the SAME server id as the live observer
     * row but carries the REAL run id. [canReplaceIrohSyntheticLiveRow] must
     * recognize the existing observer row's `iroh-observer-run-` id as synthetic
     * and PROMOTE/REPLACE it with the reconciled real-run final — one row, not
     * two.
     */
    @Test
    fun `observer live row is replaced by reconciled final on matching server id`() {
        val conversationId = "conv-j98r5"
        val serverId = "letta-msg-abc"

        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "iroh-observer-turn-$conversationId",
                    serverId = serverId,
                    content = "Hello from the observer",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-$conversationId",
                    stepId = null,
                ),
            ),
        )

        val serverMessages = listOf(
            AssistantMessage(
                id = serverId, // SAME server id as the live observer row
                contentRaw = JsonPrimitive("Hello from the observer"),
                date = "2026-07-09T00:00:00Z",
                runId = "run-real-server-123", // REAL run id from the persisted final
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(serverMessages)

        val assistantRows = merged.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(
            1,
            assistantRows.size,
            "Reconciled final must REPLACE the live observer row, not append a duplicate. " +
                "Rows: " + assistantRows.joinToString(" || ") { "[${it.serverId}/${it.runId}]" },
        )
        // The surviving row is the reconciled real-run final (promoted run id).
        assertEquals("run-real-server-123", assistantRows.single().runId)
    }

    /**
     * The reconcile final arrives with a DIFFERENT server id and a NULL run id
     * (the admin `message.list` shape) whose FULL text is a superset of the live
     * observer draft. The content-superset collapse must treat the observer
     * candidate row as a live-streamed row and replace it — one row, not two.
     */
    @Test
    fun `observer live row collapses into null-run superset reconcile final`() {
        val conversationId = "conv-j98r5"

        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "iroh-observer-turn-$conversationId",
                    serverId = "letta-msg-live",
                    content = "Still kicking",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-$conversationId",
                    stepId = null,
                ),
            ),
        )

        val serverMessages = listOf(
            AssistantMessage(
                id = "ui-msg-final", // DIFFERENT server id
                contentRaw = JsonPrimitive("Still kicking around here"),
                date = "2026-07-09T00:00:00Z",
                runId = null, // admin message.list omits run_id
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(serverMessages)

        val assistantRows = merged.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(
            1,
            assistantRows.size,
            "Null-run superset final must collapse into the observer draft, not append. " +
                "Rows: " + assistantRows.joinToString(" || ") { "[${it.serverId}] ${it.content}" },
        )
    }

    // ---------------------------------------------------------------------
    // NEGATIVE CONTROLS (letta-mobile-j98r5.1, PR #874 review — Meridian P1).
    //
    // The fix ONLY broadens the *synthetic classification* to also recognize
    // `iroh-observer-run-*`. Replacement still requires a serverId/otid/content
    // identity match; the broadened classifier must NOT cause any genuinely
    // distinct real message to collapse into the observer row. These prove the
    // replacement fires ONLY for the matching live→reconciled pair and never
    // over-collapses unrelated content. They PASS with the fix (broadening the
    // synthetic set does not loosen the identity match) and are here to guard
    // against future same-server-id / replace over-collapse regressions.
    // ---------------------------------------------------------------------

    /**
     * A live observer-synthetic assistant row PLUS a reconcile of a genuinely
     * DIFFERENT real assistant message — different serverId, different REAL run
     * id, different (non-superset) content — must remain TWO DISTINCT rows. The
     * observer→final replacement must NOT fire for an unrelated real message.
     */
    @Test
    fun `unrelated real message does not collapse into observer row`() {
        val conversationId = "conv-j98r5"

        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "iroh-observer-turn-$conversationId",
                    serverId = "letta-msg-observer",
                    content = "Observer draft reply about weather",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-$conversationId",
                    stepId = null,
                ),
            ),
        )

        val serverMessages = listOf(
            AssistantMessage(
                id = "letta-msg-unrelated", // DIFFERENT server id
                contentRaw = JsonPrimitive("Completely separate answer on taxes"), // DIFFERENT content
                date = "2026-07-09T00:00:00Z",
                runId = "run-real-other-999", // DIFFERENT, genuinely REAL run id
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(serverMessages)

        val assistantRows = merged.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(
            2,
            assistantRows.size,
            "An unrelated real assistant message must NOT collapse into the observer " +
                "row — the broadened synthetic classification must not over-collapse. " +
                "Rows: " + assistantRows.joinToString(" || ") { "[${it.serverId}/${it.runId}] ${it.content}" },
        )
        // Both distinct identities survive intact.
        val bySrv = assistantRows.associateBy { it.serverId }
        assertEquals(
            "iroh-observer-run-$conversationId",
            bySrv.getValue("letta-msg-observer").runId,
            "The live observer row must be untouched.",
        )
        assertEquals(
            "run-real-other-999",
            bySrv.getValue("letta-msg-unrelated").runId,
            "The unrelated real message must be preserved with its own real run id.",
        )
    }

    /**
     * Two DIFFERENT observer-synthetic rows for DIFFERENT logical messages must
     * not be wrongly merged, and a reconcile of ONE matching real final must
     * replace ONLY that matching row — the other observer row survives. This
     * proves the replacement targets the serverId-matched pair, not "any
     * synthetic row".
     */
    @Test
    fun `reconcile replaces only the matching observer row and leaves the other observer row intact`() {
        val conversationId = "conv-j98r5"

        val timeline = Timeline(
            conversationId = conversationId,
            events = persistentListOf(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "iroh-observer-turn-a-$conversationId",
                    serverId = "letta-msg-A",
                    content = "First observer message",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-$conversationId",
                    stepId = null,
                ),
                TimelineEvent.Confirmed(
                    position = 2.0,
                    otid = "iroh-observer-turn-b-$conversationId",
                    serverId = "letta-msg-B",
                    content = "Second observer message",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-$conversationId",
                    stepId = null,
                ),
            ),
        )

        // Reconcile the REAL final for ONLY the second row (same server id B).
        val serverMessages = listOf(
            AssistantMessage(
                id = "letta-msg-B",
                contentRaw = JsonPrimitive("Second observer message"),
                date = "2026-07-09T00:00:00Z",
                runId = "run-real-B-222",
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(serverMessages)

        val assistantRows = merged.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(
            2,
            assistantRows.size,
            "Two distinct observer messages must not be merged; only the matched one is " +
                "promoted. Rows: " + assistantRows.joinToString(" || ") { "[${it.serverId}/${it.runId}]" },
        )
        val bySrv = assistantRows.associateBy { it.serverId }
        // Row A (unmatched) is still a live observer-synthetic row.
        assertEquals(
            "iroh-observer-run-$conversationId",
            bySrv.getValue("letta-msg-A").runId,
            "The non-matching observer row must remain a live synthetic row (not promoted/collapsed).",
        )
        // Row B (matched) was promoted to the real run id.
        assertEquals(
            "run-real-B-222",
            bySrv.getValue("letta-msg-B").runId,
            "Only the server-id-matched observer row is replaced by its reconciled real final.",
        )
    }
}
