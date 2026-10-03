package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeCompiler
import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeCompilation
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeReceiptFrames
import com.letta.mobile.data.canvas.compose.ComposeReceiptItem
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.controller.node.iroh.MessageListWireProjection
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineMessageType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.14: the largest receipt the v1 caps allow stays under the size above which
 * `message.list` ships a tool return as a preview, so a reload still reads it whole and the chat
 * card keeps the bounds "Show on canvas" frames.
 *
 * Before the receipt dropped each item's derivable board id, that receipt was about 4.4 KB and
 * hydrated as a 2 KiB preview without bounds (found by C8, letta-mobile-bglj6.13).
 */
class CanvasComposeReceiptSizeTest {
    private val wire = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun theReceiptBudgetIsTheMessageListThreshold() {
        assertEquals(MessageListWireProjection.TOOL_RETURN_PROJECTION_THRESHOLD_BYTES, CanvasComposeContract.MAX_RECEIPT_BYTES)
    }

    /** Every shape at every cap: 24 top-level items, or one group of 23; checklists (the longest kind with a count). */
    @Test
    fun theLargestReceiptAtEveryCapIsUnderTheBudgetAndWasNotBefore() {
        listOf(false, true).forEach { grouped ->
            val receipt = maxReceipt(grouped)
            val slim = bytes(receipt)
            val withIds = bytes(receipt.copy(items = receipt.items.map { it.withIds(receipt.artifactId) }))
            println("receipt-size grouped=$grouped: ${slim}B without item ids (budget ${CanvasComposeContract.MAX_RECEIPT_BYTES}B), ${withIds}B with them")
            assertTrue(slim < CanvasComposeContract.MAX_RECEIPT_BYTES, "grouped=$grouped: $slim bytes")
            assertTrue(withIds > CanvasComposeContract.MAX_RECEIPT_BYTES, "grouped=$grouped: the ids were what put it over ($withIds bytes)")
            assertEquals(CanvasComposeContract.MAX_ITEMS, receipt.items.sumOf { 1 + (it.children?.size ?: 0) })
            val pieces = receipt.items.flatMap { listOf(it) + it.children.orEmpty() }
            assertNull(receipt.framesOmitted, "grouped=$grouped")
            assertNull(receipt.framesHint, "grouped=$grouped")
            pieces.forEach { assertEquals(4, it.frame?.size, "${it.key} grouped=$grouped") }
        }
    }

    /**
     * ComposeReceiptFrames' last resort (every frame dropped, framesOmitted and the hint set) is
     * returned unchecked because the artifact is already published; this pins that it never
     * exceeds the cap, on the largest receipt the v1 caps allow.
     */
    @Test
    fun theLargestReceiptWithEveryFrameDroppedAndMarkedIsUnderTheBudget() {
        listOf(false, true).forEach { grouped ->
            val receipt = maxReceipt(grouped)
            val emptied = receipt.copy(
                items = receipt.items.map { item -> item.copy(frame = null, children = item.children?.map { it.copy(frame = null) }) },
                framesOmitted = true,
                framesHint = ComposeReceiptFrames.HINT,
            )
            val size = bytes(emptied)
            assertTrue(size <= CanvasComposeContract.MAX_RECEIPT_BYTES, "grouped=$grouped: $size bytes with no frames")
        }
    }

    /**
     * The real hydrate path: the receipt in a `message.list` page, projected as the host projects
     * it, decoded and folded by the hydration reducer; the card that comes out has the bounds and
     * every piece id, derived.
     */
    @Test
    fun theLargestReceiptSurvivesTheHydratePathWithItsBounds() {
        listOf(false, true).forEach { grouped ->
            val receipt = maxReceipt(grouped)
            val body = CanvasComposeContract.json.encodeToString(ComposeReceipt.serializer(), receipt)
            val page = buildJsonArray {
                add(buildJsonObject {
                    put("id", "message-user"); put("message_type", "user_message"); put("content", "Plan it all")
                    put("date", T0); put("run_id", RUN)
                })
                add(buildJsonObject {
                    put("id", "message-call"); put("message_type", "tool_call_message"); put("date", T0); put("run_id", RUN); put("step_id", "s1")
                    putJsonObject("tool_call") {
                        put("tool_call_id", CALL); put("name", CanvasToolContract.COMPOSE); put("arguments", "{}")
                    }
                })
                add(buildJsonObject {
                    put("id", "message-return"); put("message_type", "tool_return_message"); put("date", T0); put("run_id", RUN); put("step_id", "s1")
                    put("tool_call_id", CALL); put("status", "success"); put("tool_return", body)
                })
                add(buildJsonObject {
                    put("id", "message-assistant"); put("message_type", "assistant_message"); put("content", "It's on the board.")
                    put("date", T0); put("run_id", RUN); put("step_id", "s2")
                })
            }
            val projected = assertIs<JsonArray>(MessageListWireProjection.projectMessageList(page, CONV))
            val returned = projected[2].jsonObject
            assertNull(returned["tool_return_truncated"], "grouped=$grouped: the receipt was shipped as a preview")
            assertEquals(body, (returned.getValue("tool_return") as JsonPrimitive).content)

            val messages = wire.decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), projected)
            val timeline = TimelineHydrationReducer.reduce(CONV, messages, Timeline(CONV), Timeline(CONV), emptyList()).timeline
            val attached = CanvasArtifactReceipts.attach(timeline.events)
            val narrating = timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single { it.messageType == TimelineMessageType.ASSISTANT }
            assertEquals(setOf(CanvasArtifactReceipts.eventKey(narrating)), attached.keys)
            val part = attached.values.single().single()
            assertEquals(CanvasArtifactStatus.Published, part.status)
            assertEquals(receipt.bounds, part.bounds, "grouped=$grouped: the bounds survived the reload")
            assertEquals(CanvasComposeContract.MAX_ITEMS, part.itemCount)
            val expectedIds = receipt.items.flatMap { listOf(it) + it.children.orEmpty() }.map { "cmp-${receipt.artifactId}-${it.key}" }
            assertEquals(expectedIds, part.pieceIds)
        }
    }

    /** A receipt stored before the slimming still reads, and names its pieces by the ids it carries. */
    @Test
    fun aReceiptWithItemIdsStillProjects() {
        val receipt = maxReceipt(grouped = false).let { it.copy(items = it.items.take(3).map { item -> item.withIds(it.artifactId) }) }
        val part = CanvasArtifactReceipts.receiptFor(
            TimelineEvent.Confirmed(
                position = 1.0, otid = "o", content = "", serverId = "m", messageType = TimelineMessageType.TOOL_CALL,
                date = com.letta.mobile.data.timeline.parseTimelineInstant(T0), runId = null, stepId = null,
                toolCalls = kotlinx.collections.immutable.persistentListOf(com.letta.mobile.data.model.ToolCall(id = CALL, name = CanvasToolContract.COMPOSE, arguments = "{}")),
                toolReturnContentByCallId = kotlinx.collections.immutable.persistentMapOf(CALL to bytesText(receipt)),
                toolReturnIsErrorByCallId = kotlinx.collections.immutable.persistentMapOf(CALL to false),
            ),
            com.letta.mobile.data.model.ToolCall(id = CALL, name = CanvasToolContract.COMPOSE, arguments = "{}"),
        )
        assertEquals(receipt.items.map { it.id }, part.pieceIds)
        assertEquals(receipt.bounds, part.bounds)
    }

    /**
     * The receipt the compiler and service make for a request at every cap: 24 items (or a group of
     * 23 under one), 32-character keys, a 48-character artifact id, a 120-character title, 40
     * entries per checklist; answered as a retry (the warning is the longest receipt field there is),
     * at the largest revision and with bounds printed at full float width.
     */
    private fun maxReceipt(grouped: Boolean): ComposeReceipt {
        fun key(i: Int) = "k${i.toString().padStart(2, '0')}".padEnd(32, 'x')
        fun checklist(i: Int) = buildJsonObject {
            put("kind", "CHECKLIST"); put("key", key(i))
            putJsonArray("items") { repeat(CanvasComposeContract.MAX_CHECKLIST_ITEMS) { add(buildJsonObject { put("text", "e$it") }) } }
        }
        val request = buildJsonObject {
            put("artifact_id", "a".repeat(48))
            put("title", "t".repeat(CanvasComposeContract.MAX_TITLE_CHARS))
            putJsonArray("items") {
                if (grouped) {
                    add(buildJsonObject {
                        put("kind", "GROUP"); put("key", "g".repeat(32)); put("label", "l".repeat(CanvasComposeContract.MAX_LABEL_CHARS))
                        putJsonArray("children") { repeat(CanvasComposeContract.MAX_ITEMS - 1) { add(checklist(it)) } }
                    })
                } else {
                    repeat(CanvasComposeContract.MAX_ITEMS) { add(checklist(it)) }
                }
            }
        }
        val ready = assertIs<ComposeCompilation.Ready>(CanvasComposeCompiler.compile(request as JsonObject, "") { error("named") })
        return ready.receipt(
            canvasId = "canvas-conversation-conv-" + "c".repeat(100),
            status = ComposeStatus.PUBLISHED,
            revision = Long.MAX_VALUE,
            warnings = listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING),
        ).copy(bounds = ComposeBounds(-1.2345679E7f, -1.2345679E7f, 1.2345679E7f, 1.2345679E7f))
    }

    private fun ComposeReceiptItem.withIds(artifactId: String): ComposeReceiptItem =
        copy(id = boardId(artifactId), children = children?.map { it.withIds(artifactId) })

    private fun bytesText(receipt: ComposeReceipt): String = CanvasComposeContract.json.encodeToString(ComposeReceipt.serializer(), receipt)

    private fun bytes(receipt: ComposeReceipt): Int = bytesText(receipt).encodeToByteArray().size

    private companion object {
        const val CONV = "conv-size"
        const val RUN = "run-size"
        const val CALL = "toolu_01ReceiptSize"
        const val T0 = "2026-10-01T12:00:00Z"
    }
}
