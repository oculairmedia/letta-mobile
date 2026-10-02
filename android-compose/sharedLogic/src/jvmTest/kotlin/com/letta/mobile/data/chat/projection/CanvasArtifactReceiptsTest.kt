package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeReceiptItem
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.controller.node.iroh.MessageListWireProjection
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.ToolReturnTruncation
import com.letta.mobile.data.timeline.parseTimelineInstant
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.13 (canvas_compose C8): the single-receipt rule. One part per compose call,
 * on the message that narrates it, derived from the persisted TOOL_CALL event alone.
 */
class CanvasArtifactReceiptsTest {
    private val receiptJson = fixture("receipt.json")
    private val refusalJson = fixture("error-validation.json")
    private val requestJson = fixture("request.json")

    // --- the fixture ---------------------------------------------------------------------

    @Test
    fun theFixtureReceiptProjectsToThePlansPart() {
        val events = listOf(user("u1"), call("t1", result = receiptJson), assistant("a1", "Done."))
        val part = CanvasArtifactReceipts.attach(events).values.single().single()
        // Plan 5.3, "Projected chat part".
        assertEquals("weekend-plan", part.artifactId)
        assertEquals("canvas-conversation-conv-123", part.canvasId)
        assertEquals(42L, part.revision)
        assertEquals(CanvasArtifactStatus.Published, part.status)
        assertEquals("Weekend plan", part.title)
        assertEquals(listOf(ComposeKind.TEXT, ComposeKind.CHECKLIST, ComposeKind.NOTE, ComposeKind.GROUP, ComposeKind.CARD), part.kinds)
        assertEquals(6, part.itemCount)
        assertEquals(ComposeBounds(80f, 80f, 712f, 746f), part.bounds)
        assertNull(part.error)
        assertTrue(part.canShowOnCanvas)
    }

    @Test
    fun theFixtureReceiptStaysUnderTheWireProjectionThreshold() {
        // A return above this is shipped as a preview on hydrate (pointer diet) and degrades.
        val compact = CanvasComposeContract.json.encodeToString(
            ComposeReceipt.serializer(),
            CanvasComposeContract.json.decodeFromString(ComposeReceipt.serializer(), receiptJson),
        )
        assertTrue(compact.encodeToByteArray().size < MessageListWireProjection.TOOL_RETURN_PROJECTION_THRESHOLD_BYTES)
    }

    @Test
    fun aReceiptOverTheThresholdHydratesAsAPreviewAndStillYieldsAPart() {
        // A receipt written before letta-mobile-bglj6.14, which still carried each item's board id:
        // at the caps (24 checklists, longest keys and artifact id) it is about 4.4 KB, over the
        // 4 KiB hydrate threshold, so message.list ships it as a 2 KiB preview. The degrade rule
        // keeps the card (ids read from the preview), without bounds to frame. Receipts written
        // now stay under the threshold (CanvasComposeReceiptSizeTest).
        val artifactId = "a".repeat(48)
        val items = (0 until CanvasComposeContract.MAX_ITEMS).map { i ->
            ComposeReceiptItem(key = "k$i".padEnd(32, 'x'), kind = ComposeKind.CHECKLIST, id = "cmp-$artifactId-" + "k$i".padEnd(32, 'x'), count = 40)
        }
        val worst = CanvasComposeContract.json.encodeToString(
            ComposeReceipt.serializer(),
            ComposeReceipt(
                artifactId = artifactId,
                canvasId = "canvas-conversation-" + "c".repeat(36),
                revision = Long.MAX_VALUE,
                status = ComposeStatus.PUBLISHED,
                title = "t".repeat(CanvasComposeContract.MAX_TITLE_CHARS),
                bounds = ComposeBounds(-99999f, -99999f, 99999f, 99999f),
                items = items,
            ),
        )
        val preview = worst.encodeToByteArray().decodeToString(0, MessageListWireProjection.TOOL_RETURN_PREVIEW_BYTES)
        val part = CanvasArtifactReceipts.attach(listOf(call("t1", result = preview, truncated = true))).values.single().single()
        assertEquals(CanvasArtifactStatus.Published, part.status)
        assertEquals(artifactId, part.artifactId)
        assertTrue(part.canvasId!!.startsWith("canvas-conversation-"))
        assertNull(part.bounds)
    }

    // --- where it attaches ---------------------------------------------------------------

    @Test
    fun attachesExactlyOnePartToTheNarratingMessageAfterTheCall() {
        val events = listOf(
            user("u1"),
            assistant("a0", "Let me put that on the board.", step = "s1"),
            reasoning("r1", step = "s1"),
            call("t1", result = receiptJson, step = "s1"),
            assistant("a1", "It's on the board.", step = "s2"),
            assistant("a2", "Anything else?", step = "s3"),
        )
        val attached = CanvasArtifactReceipts.attach(events)
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[4])), attached.keys)
        assertEquals(1, attached.values.single().size)
    }

    @Test
    fun prefersTheNarrationInTheCallsOwnStep() {
        val events = listOf(
            user("u1"),
            call("t1", result = receiptJson, step = "s1"),
            assistant("a1", "Another step's text", step = "s2"),
            assistant("a2", "Same step's text", step = "s1"),
        )
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[3])), CanvasArtifactReceipts.attach(events).keys)
    }

    @Test
    fun withoutNarrationAfterItFallsBackToTheLastNarrationBeforeItInTheSameStep() {
        val events = listOf(
            user("u1"),
            assistant("a0", "Other step", step = "s0"),
            assistant("a1", "Putting it on the board", step = "s1"),
            call("t1", result = receiptJson, step = "s1"),
        )
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[2])), CanvasArtifactReceipts.attach(events).keys)
    }

    @Test
    fun withNoNarrationAtAllItStaysOnTheToolCall() {
        val events = listOf(user("u1"), assistant("a0", "Other step", step = "s0"), call("t1", result = receiptJson, step = "s1"))
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[2])), CanvasArtifactReceipts.attach(events).keys)
    }

    @Test
    fun neverAttachesAcrossRunsOrTurns() {
        val events = listOf(
            user("u1"),
            call("t1", result = receiptJson, run = "run-1", step = "s1"),
            assistant("other-run", "Another run's text", run = "run-2", step = "s9"),
            user("u2"),
            assistant("next-turn", "Next turn", run = "run-1", step = "s2"),
        )
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[1])), CanvasArtifactReceipts.attach(events).keys)
    }

    @Test
    fun theProviderSafeToolNameIsTheComposeToolToo() {
        val events = listOf(call("t1", result = receiptJson, name = CanvasToolContract.COMPOSE.replace('.', '_')), assistant("a1", "Done."))
        assertEquals(1, CanvasArtifactReceipts.attach(events).values.single().size)
        assertTrue(CanvasArtifactReceipts.attach(listOf(call("t2", result = receiptJson, name = "canvas_apply_ops"))).isEmpty())
    }

    // --- status ----------------------------------------------------------------------------

    @Test
    fun pendingFlipsToPublishedInPlaceOnTheSameMessage() {
        val pending = listOf(user("u1"), call("t1", result = null, arguments = requestJson), assistant("a1", "Adding it."))
        val before = CanvasArtifactReceipts.attach(pending)
        val pendingPart = before.values.single().single()
        assertEquals(CanvasArtifactStatus.Pending, pendingPart.status)
        assertEquals("weekend-plan", pendingPart.artifactId)
        assertEquals("Weekend plan", pendingPart.title)
        assertEquals(6, pendingPart.itemCount)
        assertNull(pendingPart.bounds)
        assertFalse(pendingPart.canShowOnCanvas)

        val settled = pending.toMutableList().also { it[1] = call("t1", result = receiptJson, arguments = requestJson) }
        val after = CanvasArtifactReceipts.attach(settled)
        assertEquals(before.keys, after.keys)
        val published = after.values.single().single()
        assertEquals(pendingPart.artifactId, published.artifactId)
        assertEquals(CanvasArtifactStatus.Published, published.status)
    }

    @Test
    fun aRefusalIsAFailedCardWithTheBoardsReason() {
        val events = listOf(call("t1", result = refusalJson, isError = true, arguments = requestJson), assistant("a1", "That failed."))
        val part = CanvasArtifactReceipts.attach(events).values.single().single()
        assertEquals(CanvasArtifactStatus.Failed, part.status)
        assertEquals("VALIDATION_FAILED", part.error?.code)
        assertEquals("'STICKY' is not a kind; use NOTE, CHECKLIST, CARD, TEXT or GROUP", part.error?.message)
        assertEquals(3, part.error?.problemCount)
        assertNull(part.bounds)
        assertFalse(part.canShowOnCanvas)
        // Named from the request, so the card still says what was attempted.
        assertEquals("weekend-plan", part.artifactId)
        assertEquals("Weekend plan", part.title)
    }

    @Test
    fun aPlainTextErrorIsAFailedCardCarryingThatText() {
        val part = CanvasArtifactReceipts.attach(listOf(call("t1", result = "Unknown canvas", isError = true))).values.single().single()
        assertEquals(CanvasArtifactStatus.Failed, part.status)
        assertEquals("Unknown canvas", part.error?.message)
    }

    @Test
    fun aDryRunIsAPreviewNotAPublication() {
        val dryRun = receiptJson.replace("\"published\"", "\"dry_run\"")
        val part = CanvasArtifactReceipts.attach(listOf(call("t1", result = dryRun))).values.single().single()
        assertEquals(CanvasArtifactStatus.DryRun, part.status)
        assertFalse(part.canShowOnCanvas)
    }

    @Test
    fun aTruncatedReturnDegradesToAPartWithoutBoundsNeverToNothing() {
        val preview = receiptJson.take(120)
        val events = listOf(call("t1", result = preview, truncated = true, arguments = requestJson), assistant("a1", "Done."))
        val part = CanvasArtifactReceipts.attach(events).values.single().single()
        assertEquals(CanvasArtifactStatus.Published, part.status)
        assertEquals("weekend-plan", part.artifactId)
        assertEquals("Weekend plan", part.title)
        assertNull(part.bounds)
        // Even a full-looking body is not trusted while the marker says it is a preview.
        val markedFull = CanvasArtifactReceipts.attach(listOf(call("t2", result = receiptJson, truncated = true))).values.single().single()
        assertNull(markedFull.bounds)
        assertEquals("weekend-plan", markedFull.artifactId)
    }

    @Test
    fun anUnreadableReturnDegradesTooAndReadsItsIdsFromTheText() {
        val part = CanvasArtifactReceipts.attach(listOf(call("t1", result = "{\"artifact_id\":\"x-1\",\"canvas_id\":\"canvas-9\", broken")))
            .values.single().single()
        assertEquals(CanvasArtifactStatus.Published, part.status)
        assertEquals("x-1", part.artifactId)
        assertEquals("canvas-9", part.canvasId)
        assertNull(part.bounds)
    }

    // --- dedupe -----------------------------------------------------------------------------

    @Test
    fun theSameCallDeliveredTwiceYieldsOnePart() {
        // A replayed or observed delivery of the call under another message id, plus the Local echo.
        val events = listOf(
            user("u1"),
            localCall("t1", result = null),
            call("t1", result = receiptJson, serverId = "m-live"),
            call("t1", result = receiptJson, serverId = "m-replay"),
            assistant("a1", "Done."),
        )
        val attached = CanvasArtifactReceipts.attach(events)
        val part = attached.values.flatten().single()
        assertEquals(CanvasArtifactStatus.Published, part.status)
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[4])), attached.keys)
    }

    @Test
    fun aRetryUnderTheSameArtifactIdYieldsOnePartWithTheLatestStatus() {
        val events = listOf(
            user("u1"),
            call("t1", result = refusalJson, isError = true, arguments = requestJson, step = "s1"),
            assistant("a1", "Let me fix that.", step = "s2"),
            call("t2", result = receiptJson, arguments = requestJson, step = "s3"),
            assistant("a2", "Done.", step = "s4"),
        )
        val attached = CanvasArtifactReceipts.attach(events)
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(events[4])), attached.keys)
        assertEquals(CanvasArtifactStatus.Published, attached.values.single().single().status)
    }

    @Test
    fun twoArtifactsInOneRunKeepTheirOrder() {
        val second = receiptJson.replace("weekend-plan", "second")
        val events = listOf(user("u1"), call("t1", result = receiptJson, step = "s1"), call("t2", result = second, step = "s2"), assistant("a1", "Both done."))
        assertEquals(listOf("weekend-plan", "second"), CanvasArtifactReceipts.attach(events).values.single().map { it.artifactId })
    }

    // --- durability -------------------------------------------------------------------------

    @Test
    fun hydratingTheSameEventsFromAStoredEnvelopeAttachesTheSameParts() {
        val live = listOf(
            user("u1"),
            call("t1", result = receiptJson, arguments = requestJson, step = "s1"),
            assistant("a1", "It's on the board.", step = "s2"),
        )
        val envelope = StoredTimelineEnvelope(
            scope = TimelineScope("backend-1", "conv-123"),
            revision = 1,
            events = live.map { it.toStoredTimelineEvent() },
        )
        val text = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEnvelope.serializer(), envelope)
        val hydrated = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEnvelope.serializer(), text)
            .events.map(StoredTimelineEvent::toConfirmedTimelineEvent)
        assertEquals(CanvasArtifactReceipts.attach(live), CanvasArtifactReceipts.attach(hydrated))
        // Derived only: the stored schema did not grow for it.
        assertEquals(1, StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION)
        assertFalse(text.contains("artifacts"))
    }

    @Test
    fun noComposeCallsMeansNoMapAndUntouchedMessages() {
        val events = listOf(user("u1"), assistant("a1", "Hi"))
        val attached = CanvasArtifactReceipts.attach(events)
        assertTrue(attached.isEmpty())
        val message = timelineEventToUiMessage(events[1])!!
        with(CanvasArtifactReceipts) { assertSame(message, attached.applyTo(events[1], message)) }
    }

    @Test
    fun theMapperCarriesTheAttachedPartsOnBothBranches() {
        val events = listOf(user("u1"), call("t1", result = receiptJson), assistant("a1", "Done."))
        val attached = CanvasArtifactReceipts.attach(events)
        val parts = attached.getValue(CanvasArtifactReceipts.eventKey(events[2]))
        assertEquals(parts, timelineEventToUiMessage(events[2], artifacts = parts)?.artifacts)
        val local = TimelineEvent.Local(
            position = 9.0, otid = "l-1", content = "Done.", sentAt = parseTimelineInstant(T0),
            deliveryState = DeliveryState.SENT, messageType = TimelineMessageType.ASSISTANT,
        )
        assertEquals(parts, timelineEventToUiMessage(local, artifacts = parts)?.artifacts)
    }

    // --- helpers ----------------------------------------------------------------------------

    private var position = 0.0

    private fun next(): Double {
        position += 1.0
        return position
    }

    private fun user(id: String) = confirmed(id, TimelineMessageType.USER, "Plan my weekend", run = null, step = null)

    private fun assistant(id: String, text: String, run: String? = RUN, step: String? = "s1") =
        confirmed(id, TimelineMessageType.ASSISTANT, text, run, step)

    private fun reasoning(id: String, step: String) = confirmed(id, TimelineMessageType.REASONING, "thinking", RUN, step)

    private fun confirmed(id: String, type: TimelineMessageType, text: String, run: String?, step: String?) = TimelineEvent.Confirmed(
        position = next(), otid = "otid-$id", content = text, serverId = id, messageType = type,
        date = parseTimelineInstant(T0), runId = run, stepId = step,
    )

    private fun call(
        callId: String,
        result: String?,
        isError: Boolean = false,
        truncated: Boolean = false,
        arguments: String = "{}",
        name: String = CanvasToolContract.COMPOSE,
        run: String = RUN,
        step: String = "s1",
        serverId: String = "m-$callId",
    ) = TimelineEvent.Confirmed(
        position = next(), otid = "otid-$serverId", content = "", serverId = serverId,
        messageType = TimelineMessageType.TOOL_CALL, date = parseTimelineInstant(T0), runId = run, stepId = step,
        toolCalls = persistentListOf(ToolCall(id = callId, name = name, arguments = arguments)),
        toolReturnContentByCallId = if (result == null) persistentMapOf() else persistentMapOf(callId to result),
        toolReturnIsErrorByCallId = if (result == null) persistentMapOf() else persistentMapOf(callId to isError),
        toolReturnTruncationByCallId = if (truncated) persistentMapOf(callId to ToolReturnTruncation("ret-$callId", 9999L)) else persistentMapOf(),
    )

    private fun localCall(callId: String, result: String?) = TimelineEvent.Local(
        position = next(), otid = "local-$callId", content = "", sentAt = parseTimelineInstant(T0),
        deliveryState = DeliveryState.SENT, messageType = TimelineMessageType.TOOL_CALL,
        toolCalls = persistentListOf(ToolCall(id = callId, name = CanvasToolContract.COMPOSE, arguments = "{}")),
        toolReturnContentByCallId = if (result == null) persistentMapOf() else persistentMapOf(callId to result),
    )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/canvas/compose/v1/$name")) { "missing fixture $name" }.readText()

    private companion object {
        const val RUN = "run-1"
        const val T0 = "2026-10-01T12:00:00Z"
    }
}
