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
}
