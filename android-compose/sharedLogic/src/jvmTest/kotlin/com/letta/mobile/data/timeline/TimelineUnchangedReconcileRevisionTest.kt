package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-o1hju S1 (hypothesis H2, invariant I2): the post-send maintenance reconciles
 * (ChatSendCoordinator.postSendReconcileDelaysMs: 750 ms, 2.5 s, 6 s) read the same recent page
 * again after a turn has settled. A page that changes nothing must leave the durable revision
 * where the settle put it, for EVERY row kind the canonical writer stores, because each new
 * revision replaces the Paging generation under the list (generation.replaced on the phone).
 *
 * The kinds are enumerated from the writer (TimelineExactCanonicalWriter.merge): events from
 * LettaMessage.toTimelineEvent (user, assistant, reasoning, tool call, approval request, system,
 * error, compaction), a tool return folded onto its call or deferred ahead of it, an image body,
 * and the opaque rows (stop reason, usage, hidden reasoning, approval response, event). Every
 * page is decoded from wire JSON for each reconcile, as the App Server transport does, so a field
 * the client fills in at decode time is fresh on every pass exactly as on a device.
 *
 * Real engine and writer over the shared in-memory store; the delays only name the slots, the
 * engine has no clock in this path.
 */
class TimelineUnchangedReconcileRevisionTest {
    /** I2 per row kind: each kind on its own turn, in both list orders the transport may return. */
    @Test fun unchangedReconcileKeepsTheRevisionForEveryStoredRowKind() = runTest {
        val advanced = rowKinds.flatMap { kind ->
            TimelineRemoteOrder.entries.filter { it != TimelineRemoteOrder.Unknown }.flatMap { order ->
                revisionsAdvancedBySchedule(InMemoryTimelineStore()) { kind.page(order) }
                    .map { "${kind.name} [$order] advanced at the $it ms reconcile" }
            }
        }
        assertTrue(advanced.isEmpty(), "an unchanged reconcile advanced the durable revision:\n" + advanced.joinToString("\n"))
    }

    /**
     * The Read image body as the phone stores it: the ledger keeps image bytes out of line
     * (RoomTimelineBoundedStore.persistImage: content-addressed, idempotent), so the row carries a
     * body reference rather than the base64.
     */
    @Test fun unchangedReconcileKeepsTheRevisionForAReadImageBodyStoredOutOfLine() = runTest {
        val advanced = TimelineRemoteOrder.entries.filter { it != TimelineRemoteOrder.Unknown }.flatMap { order ->
            revisionsAdvancedBySchedule(ImageBodyStore()) { readImage.page(order) }
                .map { "${readImage.name} [$order] advanced at the $it ms reconcile" }
        }
        assertTrue(advanced.isEmpty(), "an unchanged reconcile advanced the durable revision:\n" + advanced.joinToString("\n"))
    }

    /** I2/I3 for one finished turn holding every kind at once, settled after its live stream. */
    @Test fun unchangedReconcileScheduleKeepsTheRevisionAfterAFinishedTurn() = runTest {
        val store = InMemoryTimelineStore()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, MAX_BODY_BYTES), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        wholeTurn(TimelineRemoteOrder.OldestFirst).filter { it.renders() }
            .forEach { assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(hostStamped(it)))) }
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, wholeTurn(TimelineRemoteOrder.NewestFirst)))
        val settled = engine.publication.value.durableRevision
        val revisions = POST_SEND_DELAYS_MS.map { delay ->
            assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, wholeTurn(TimelineRemoteOrder.NewestFirst)))
            delay to engine.publication.value.durableRevision
        }
        assertEquals(
            POST_SEND_DELAYS_MS.map { it to settled }, revisions,
            "the post-send reconcile schedule advanced the durable revision (settle was revision $settled)",
        )
    }

    /** Control for R2: a genuine server-side edit must still advance the revision. */
    @Test fun aGenuineEditStillAdvancesTheRevision() = runTest {
        val store = InMemoryTimelineStore()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, MAX_BODY_BYTES), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val page = wholeTurn(TimelineRemoteOrder.NewestFirst)
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, page))
        val settled = engine.publication.value.durableRevision
        val edited = page.map {
            if (it.id == "msg-reply") {
                decode(wire(WireRow("msg-reply", "assistant_message", RUN, 20, """"content":"Edited."""")))
            } else {
                it
            }
        }
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, edited))
        assertNotEquals(settled, engine.publication.value.durableRevision, "a real edit must replace the generation")
    }

    /** The slots of the schedule whose reconcile advanced the revision past the previous one. */
    private suspend fun revisionsAdvancedBySchedule(store: TimelineBoundedStore, page: () -> List<LettaMessage>): List<Long> {
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, MAX_BODY_BYTES), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, page()))
        var revision = engine.publication.value.durableRevision
        return POST_SEND_DELAYS_MS.filter { _ ->
            assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, page()))
            val next = engine.publication.value.durableRevision
            (next != revision).also { revision = next }
        }
    }

    private suspend fun reconcile(
        engine: CanonicalTimelineEngine,
        selection: TimelineEngineSelection,
        messages: List<LettaMessage>,
    ): TimelineEnginePageOutcome {
        val request = engine.beginReconcile(selection)
        return engine.reconcilePage(request, TimelineRemotePageResult.Page(
            request.remote.requestId, selection.generation,
            messages.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) }, null, false, 0,
        ))
    }

    /** One row kind on a turn of its own: the prompt that started it, then the kind's wire rows. */
    private class RowKind(val name: String, val rows: List<String>) {
        fun page(order: TimelineRemoteOrder): List<LettaMessage> {
            val oldestFirst = (listOf(prompt) + rows).map(::decode)
            return if (order == TimelineRemoteOrder.NewestFirst) oldestFirst.reversed() else oldestFirst
        }
    }

    /**
     * Records a body the way RoomTimelineBoundedStore.persistImage does: named by the SHA-256 of
     * its decoded bytes, written once, so the same image always yields the same reference. Every
     * other call is the shared in-memory store's.
     */
    private class ImageBodyStore(private val ledger: InMemoryTimelineStore = InMemoryTimelineStore()) :
        TimelineBoundedStore by ledger {
        private val bodies = mutableMapOf<String, ByteArray>()

        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
            ledger.transaction(scope) {
                val delegate = this
                block(object : TimelineStoreTransaction by delegate, TimelineImageBodyWriter {
                    override suspend fun persistImage(base64: String): StoredImageBodyReference {
                        val bytes = Base64.getDecoder().decode(base64)
                        require(bytes.isNotEmpty())
                        require(Base64.getEncoder().encodeToString(bytes) == base64) { "Non-canonical image base64" }
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        bodies.getOrPut(hash) { bytes }
                        return StoredImageBodyReference(hash, bytes.size.toLong())
                    }

                    override suspend fun resolveImage(reference: StoredImageBodyReference): String? =
                        bodies[reference.sha256]?.let { Base64.getEncoder().encodeToString(it) }
                })
            }
    }

    private companion object {
        val scope = TimelineScope("backend", "conv-reconcile", "agent")
        const val RUN = "local-run-125"
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        val POST_SEND_DELAYS_MS = listOf(750L, 2_500L, 6_000L)
        val json = Json { ignoreUnknownKeys = true }
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="

        fun decode(row: String): LettaMessage = json.decodeFromString(LettaMessage.serializer(), row)

        /** One wire row; [second] orders it inside the turn. */
        private data class WireRow(val id: String?, val type: String, val run: String?, val second: Int, val fields: String)

        fun wire(row: WireRow): String =
            "{" + listOfNotNull(
                row.id?.let { "\"id\":\"$it\"" }, "\"message_type\":\"${row.type}\"", row.run?.let { "\"run_id\":\"$it\"" },
                "\"date\":\"2026-11-14T15:54:%02d.000Z\"".format(row.second), row.fields,
            ).joinToString(",") + "}"

        val prompt = wire(WireRow("msg-prompt", "user_message", null, 0, """"content":"check the build","otid":"cm-prompt""""))
        val reasoning = wire(WireRow("msg-thought", "reasoning_message", RUN, 1, """"reasoning":"Look at the log first.""""))
        val call = wire(WireRow("msg-call", "tool_call_message", RUN, 2,
            """"tool_call":{"tool_call_id":"call-bash","name":"Bash","arguments":"{\"command\":\"make\"}"}"""))
        val callReturn = wire(WireRow("msg-call-return", "tool_return_message", RUN, 3,
            """"tool_call_id":"call-bash","status":"success","tool_return":"ok""""))
        val approval = wire(WireRow("msg-approval", "approval_request_message", RUN, 4,
            """"tool_call":{"tool_call_id":"call-approve","name":"Write","arguments":"{}"}"""))
        val approvalReturn = wire(WireRow("msg-approval-return", "tool_return_message", RUN, 5,
            """"tool_call_id":"call-approve","status":"success","tool_return":"written""""))
        val approvalResponse = wire(WireRow("msg-approval-response", "approval_response_message", RUN, 6,
            """"approve":true,"approval_request_id":"msg-approval""""))
        val readCall = wire(WireRow("msg-read", "tool_call_message", RUN, 7,
            """"tool_call":{"tool_call_id":"call-read","name":"Read","arguments":"{}"}"""))
        val readReturn = wire(WireRow("msg-read-return", "tool_return_message", RUN, 8,
            """"tool_call_id":"call-read","status":"success","tool_return":[{"type":"text","text":"[Image: pixel.png]"},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$PNG"}}]"""))
        val narration = wire(WireRow("msg-narration", "assistant_message", RUN, 9, """"content":"Build is green.""""))
        val followUp = wire(WireRow("msg-follow-up", "user_message", null, 10, """"content":"and the tests?","otid":"cm-follow-up""""))
        val followUpInRun = wire(
            WireRow("msg-follow-up-run", "user_message", RUN, 10, """"content":"and lint?","otid":"cm-follow-up-run""""),
        )
        val backgroundNotice = wire(WireRow("msg-notice", "user_message", null, 11,
            """"content":"<task-notification>\n<task-id>bash_7</task-id>\n<status>completed</status>\n""" +
                """<summary>Background command \"make test\" completed</summary>\n</task-notification>""""))
        val hiddenReasoning = wire(WireRow("msg-hidden", "hidden_reasoning_message", RUN, 12, """"state":"redacted""""))
        val event = wire(WireRow("msg-event", "event_message", RUN, 13, """"event_type":"context_window_overflow""""))
        val system = wire(WireRow("msg-system", "system_message", null, 14, """"content":"Memory updated.""""))
        val error = wire(WireRow("msg-error", "error_message", RUN, 15, """"message":"rate limited""""))
        val summary = wire(WireRow("msg-summary", "summary_message", null, 16, """"summary":"Earlier turns compacted.""""))
        val reply = wire(WireRow("msg-reply", "assistant_message", RUN, 20, """"content":"Tests pass.""""))
        val usage = wire(WireRow("msg-usage", "usage_statistics", RUN, 21, """"prompt_tokens":1000,"completion_tokens":14"""))
        val stop = wire(WireRow("msg-stop", "stop_reason", RUN, 21, """"stop_reason":"end_turn""""))
        val readImage = RowKind("Read image body (tool return image)", listOf(readCall, readReturn))

        val rowKinds = listOf(
            RowKind("user prompt", emptyList()),
            RowKind("assistant prose", listOf(reply)),
            RowKind("reasoning", listOf(reasoning)),
            RowKind("tool call + return", listOf(call, callReturn)),
            RowKind("tool return ahead of its call (deferred)", listOf(callReturn, call)),
            RowKind("approval request + return", listOf(approval, approvalReturn)),
            readImage,
            RowKind("mid-turn follow-up prompt (#2460)", listOf(narration, followUp, reply)),
            RowKind("mid-turn follow-up prompt inside the run", listOf(narration, followUpInRun, reply)),
            RowKind("background command notice", listOf(backgroundNotice)),
            RowKind("system message", listOf(system)),
            RowKind("error", listOf(error)),
            RowKind("compaction summary", listOf(summary)),
            RowKind("usage statistics", listOf(usage)),
            RowKind("stop reason", listOf(stop)),
            RowKind("hidden reasoning (opaque)", listOf(hiddenReasoning)),
            RowKind("approval response (opaque)", listOf(approvalResponse)),
            RowKind("event message (opaque)", listOf(event)),
        )

        fun wholeTurn(order: TimelineRemoteOrder): List<LettaMessage> {
            val rows = listOf(
                prompt, reasoning, call, callReturn, approval, approvalReturn, approvalResponse, readCall, readReturn,
                narration, followUp, backgroundNotice, hiddenReasoning, event, error, reply, usage, stop,
            ).map(::decode)
            return if (order == TimelineRemoteOrder.NewestFirst) rows.reversed() else rows
        }

        fun LettaMessage.renders(): Boolean = toTimelineEvent(0.0) != null
    }
}
