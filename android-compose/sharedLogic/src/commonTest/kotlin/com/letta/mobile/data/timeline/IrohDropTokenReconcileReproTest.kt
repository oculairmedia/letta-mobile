package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FAITHFUL, device-free repro of the Iroh streamed-message DUPLICATION bug.
 *
 * This drives the REAL Android receive machinery end to end with no shortcuts:
 *
 *   1. reduceStreamFrame            — the REAL reducer (incl. mergeStreamText),
 *                                     fed the REAL captured Iroh assistant
 *                                     stream_delta fragments.
 *   2. Timeline.mergeServerMessages — the REAL send-path reconciler, fed the
 *                                     authoritative `message.list` snapshot
 *                                     exactly as the admin backend returns it
 *                                     (ui-msg-* id, run_id absent, FULL text).
 *
 * ROOT CAUSE (proven by [reducer_preserves_every_streamed_token]): the "dropped
 * token" is NOT dropped by the network — it is dropped by the reducer. When the
 * incremental one-char token "I" (seq 99, right after the ").\n\n" paragraph
 * break) arrives, mergeStreamText's STALE branch fires because the accumulated
 * text ALREADY starts with "I" ("I'm Lester ..."). A genuine forward increment
 * is misclassified as a stale prefix-snapshot and discarded, so the streamed row
 * loses one character.
 *
 * CONSEQUENCE (proven by [full_receive_pathway_yields_one_assistant_row]): that
 * one lost character made the streamed row's content neither a prefix, a
 * substring, nor an exact match of the reconciled final, and their ids/otid/run
 * all differ — so every matcher in mergeServerMessages missed and the final was
 * inserted as a SECOND assistant row. That is the duplicate.
 *
 * These are the red repro the reconcile-stubbing HeadlessTimelineReplayer could
 * never produce. They are GREEN now that mergeStreamText's incremental-append
 * mode (incrementalForwardAppend, enabled on the otid Iroh path) stops dropping a
 * forward token that coincides with a prefix/suffix of the accumulated text.
 */
class IrohDropTokenReconcileReproTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** The REAL captured Iroh assistant fragments (single reply). */
    private val realFrames: List<String> = IrohRealCapturedFrames.FRAMES

    /** Ground-truth full reply: the exact concatenation of every streamed token. */
    private fun fullAssistantText(): String = buildString {
        realFrames.forEach {
            append((json.decodeFromString(LettaMessage.serializer(), it) as AssistantMessage).content)
        }
    }

    private fun reduceStream(): Timeline {
        var tl = Timeline(conversationId = CONVERSATION_ID)
        realFrames.forEach { raw ->
            val msg = json.decodeFromString(LettaMessage.serializer(), raw)
            tl = reduceStreamFrame(
                TimelineReducerInput(
                    prev = tl,
                    frame = msg,
                    pendingToolReturnsByCallId = persistentMapOf(),
                )
            ).next
        }
        return tl
    }

    /**
     * The authoritative message.list snapshot the reconciler polls after the turn.
     * Mirrors the admin HTTP/RPC shape: its OWN ui-msg-* id (never letta-msg-*),
     * NO run_id, NO otid, and the FULL text.
     */
    private fun reconcileSnapshot(fullText: String): LettaMessage {
        val obj: JsonObject = buildJsonObject {
            put("id", JsonPrimitive("ui-msg-final-h30cy"))
            put("otid", JsonPrimitive("ui-msg-final-h30cy"))
            // run_id intentionally absent — matches the admin snapshot shape.
            put("seq_id", JsonPrimitive(200))
            put("date", JsonPrimitive("2026-07-05T00:00:00Z"))
            put("message_type", JsonPrimitive("assistant_message"))
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(fullText))
                        }
                    )
                },
            )
        }
        return json.decodeFromString(
            LettaMessage.serializer(),
            json.encodeToString(JsonObject.serializer(), obj),
        )
    }

    private fun assistantRows(tl: Timeline) =
        tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

    /** Isolates the root cause: the reducer must not lose any streamed token. */
    @Test
    fun reducer_preserves_every_streamed_token() {
        val full = fullAssistantText()
        val streamed = assistantRows(reduceStream()).single().content
        assertEquals(
            full, streamed,
            "reducer dropped/garbled a token: streamed len=${streamed.length}, full len=${full.length}",
        )
    }

    /** The end-to-end symptom: streamed row + reconcile final must be ONE row. */
    @Test
    fun full_receive_pathway_yields_one_assistant_row() {
        val full = fullAssistantText()
        val tl = reduceStream().mergeServerMessages(listOf(reconcileSnapshot(full))).first
        val rows = assistantRows(tl)
        assertEquals(
            1, rows.size,
            "reduce + reconcile produced ${rows.size} assistant rows (duplicate). Rows: " +
                rows.joinToString(" || ") { "[${it.serverId}] …${it.content.takeLast(28)}" },
        )
    }

    private companion object {
        const val CONVERSATION_ID = "conv-c297ed6c"
    }
}
