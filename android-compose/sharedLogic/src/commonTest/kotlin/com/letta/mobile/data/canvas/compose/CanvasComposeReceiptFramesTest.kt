package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Receipt frames (letta-mobile-i9yps.2): the rectangle placement wrote, and a loud omission when
 * the frames would push the receipt over 4 KiB.
 */
class CanvasComposeReceiptFramesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun framesMatchPublishedFrames() = runTest {
        val host = HostCanvasComposeToolsTest.Host()
        val receipt = json.decodeFromString(
            ComposeReceipt.serializer(),
            (host.compose(REQUEST) as ExternalToolResult.Success).content,
        )
        val scene = host.scene().sceneJson
        val documents = CanvasOpProjector.documentsOf(scene).associateBy { it.id }
        receipt.items.flatMap { listOf(it) + it.children.orEmpty() }.forEach { item ->
            val id = item.boardId(receipt.artifactId)
            val document = documents[id]
            if (document != null) {
                assertEquals(document.frame!!.frameInts(), item.frame, id)
            } else {
                assertEquals(elementFrame(scene, id), item.frame, id)
            }
        }
        assertNull(receipt.framesOmitted)
    }

    @Test
    fun overBudgetOmitsFramesLoudly() {
        val withChildren = Bulk(parentFrames = true, childFrames = true).justOver { it.copy(canvasExtra = it.canvasExtra + 1) }
        val fittedChildren = ComposeReceiptFrames.fit(withChildren.receipt())
        assertTrue(bytes(withChildren.receipt()) > CanvasComposeContract.MAX_RECEIPT_BYTES)
        assertTrue(bytes(fittedChildren) <= CanvasComposeContract.MAX_RECEIPT_BYTES)
        assertEquals(true, fittedChildren.framesOmitted)
        assertEquals(ComposeReceiptFrames.HINT, fittedChildren.framesHint)
        fittedChildren.items.forEach { item ->
            assertEquals(FRAME, item.frame, "a top-level frame stays when dropping children is enough")
            item.children.orEmpty().forEach { assertNull(it.frame, it.key) }
        }

        val parentsOnly = Bulk(parentFrames = true, childFrames = false).justOver { it.copy(warnPad = it.warnPad + 1) }
        val fittedAll = ComposeReceiptFrames.fit(parentsOnly.receipt())
        assertTrue(bytes(parentsOnly.receipt()) > CanvasComposeContract.MAX_RECEIPT_BYTES)
        assertTrue(bytes(fittedAll) <= CanvasComposeContract.MAX_RECEIPT_BYTES)
        assertEquals(true, fittedAll.framesOmitted)
        assertEquals(ComposeReceiptFrames.HINT, fittedAll.framesHint)
        fittedAll.items.forEach { item -> item.children.orEmpty().forEach { assertNull(it.frame) } }
        val kept = fittedAll.items.map { it.frame }
        assertEquals(FRAME, kept.first(), "frames drop from the end, so the first item keeps its frame")
        assertNull(kept.last(), "the last frame is the one that was over the cap")
        assertTrue(kept.any { it == null } && kept.any { it != null })
    }

    @Test
    fun aRetryReportsTheFrameAPersonMoved() = runTest {
        val host = HostCanvasComposeToolsTest.Host()
        val first = HostCanvasComposeToolsTest.receipt(host.compose(REQUEST))
        val noteId = "cmp-frames-n1"
        val document = CanvasOpProjector.documentsOf(host.scene().sceneJson).single { it.id == noteId }
        val moved = buildJsonObject {
            put("ops", buildJsonArray {
                add(buildJsonObject {
                    put("type", "set_document")
                    put("documentId", noteId)
                    put("documentJson", document.json)
                    putJsonObject("frame") {
                        put("x", 400)
                        put("y", 20)
                        put("width", 140)
                        put("height", 80)
                    }
                })
            })
        }
        assertTrue(host.registry.invoke(
            CanvasToolContract.APPLY_OPS,
            moved,
            agentId = HostCanvasComposeToolsTest.AGENT,
            conversationId = HostCanvasComposeToolsTest.CONVERSATION,
        ) is ExternalToolResult.Success)
        val again = HostCanvasComposeToolsTest.receipt(host.compose(REQUEST))
        val note = again.items.single { it.key == "n1" }
        assertEquals(listOf(400, 20, 140, 80), note.frame)
        assertNull(again.framesOmitted)
        assertEquals(first.items.single { it.key == "g1" }.frame, again.items.single { it.key == "g1" }.frame)
    }

    @Test
    fun aRetryOmitsAFrameTheBoardNoLongerHas() = runTest {
        val host = HostCanvasComposeToolsTest.Host()
        HostCanvasComposeToolsTest.receipt(host.compose(REQUEST))
        val scene = json.parseToJsonElement(host.scene().sceneJson).jsonObject.toMutableMap()
        val documents = (scene["_documents"] as JsonArray).map { entry ->
            val obj = entry.jsonObject
            if (obj.string("id") == "cmp-frames-n1") JsonObject(obj.filterKeys { it != "frame" }) else obj
        }
        scene["_documents"] = JsonArray(documents)
        val ready = assertIs<ComposeCompilation.Ready>(
            CanvasComposeCompiler.compile(json.parseToJsonElement(REQUEST), JsonObject(scene).toString()) { error("named") },
        )
        val note = ready.receipt(ComposeBoard.CANVAS, ComposeStatus.PUBLISHED, 2)
            .items.single { it.key == "n1" }
        assertNull(note.frame, "a missing stored frame is omitted, not a fresh placement")
    }

    @Test
    fun frameIntsRoundsEdgesHalfUp() {
        assertEquals(listOf(3, -2, 0, 0), Slot(2.5f, -2.5f, 0f, 0f).frameInts())
        assertEquals(listOf(-2, 2, 0, 0), Slot(-2.4f, 2.4999f, 0f, 0f).frameInts())
        assertEquals(listOf(1_000_001, 0, 0, 0), Slot(1_000_000.5f, 0f, 0f, 0f).frameInts())
        assertEquals(listOf(11, 0, 30, 1), Slot(10.5f, 0f, 30.5f, 1f).frameInts())
    }

    private fun elementFrame(scene: String, id: String): List<Int> {
        val elements = (json.parseToJsonElement(scene).jsonObject["elements"] as JsonArray).map { it.jsonObject }
        val element = elements.single { it.string("id") == id }
        val points = element["points"] as JsonArray
        val parsed = points.map { it.jsonPrimitiveContent().split(",") }
        val xs = parsed.map { it[0].toFloat() }
        val ys = parsed.map { it[1].toFloat() }
        return Slot(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min()).frameInts()
    }

    /**
     * Grows [step] one byte at a time until the encoded receipt passes the cap, so the overflow is
     * the frames and not a multi-kilobyte id.
     */
    private fun Bulk.justOver(step: (Bulk) -> Bulk): Bulk {
        var current = this
        repeat(CanvasComposeContract.MAX_RECEIPT_BYTES) {
            if (bytes(current.receipt()) > CanvasComposeContract.MAX_RECEIPT_BYTES) return current
            current = step(current)
        }
        error("the receipt never passed ${CanvasComposeContract.MAX_RECEIPT_BYTES} bytes")
    }

    /** A full-size item list whose canvas id and warning are the knobs that cross the byte cap. */
    private data class Bulk(
        val canvasExtra: Int = 1,
        val warnPad: Int = 1,
        val parentFrames: Boolean,
        val childFrames: Boolean,
    ) {
        fun receipt(): ComposeReceipt {
            val items = List(CanvasComposeContract.MAX_ITEMS) { index ->
                val key = "k${index.toString().padStart(2, '0')}"
                ComposeReceiptItem(
                    key = key,
                    kind = ComposeKind.GROUP,
                    frame = FRAME.takeIf { parentFrames },
                    children = listOf(
                        ComposeReceiptItem(
                            key = "c${index.toString().padStart(2, '0')}",
                            kind = ComposeKind.NOTE,
                            frame = FRAME.takeIf { childFrames },
                        ),
                    ),
                )
            }
            return ComposeReceipt(
                artifactId = "artifact",
                canvasId = "canvas-" + "c".repeat(canvasExtra),
                revision = Long.MAX_VALUE,
                status = ComposeStatus.PUBLISHED,
                title = "Plan",
                bounds = ComposeBounds(0f, 0f, 320f, 240f),
                items = items,
                warnings = listOf("w".repeat(warnPad)),
            )
        }
    }

    private fun bytes(receipt: ComposeReceipt): Int =
        CanvasComposeContract.json.encodeToString(ComposeReceipt.serializer(), receipt).encodeToByteArray().size

    private fun CanvasDocumentFrame.frameInts(): List<Int> = Slot(x, y, width, height).frameInts()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as JsonPrimitive).content

    private companion object {
        val FRAME = listOf(8, 8, 32, 40)
        const val REQUEST = """{"artifact_id":"frames","title":"Frames","items":[
            {"kind":"NOTE","key":"n1","title":"One","markdown":"Hello"},
            {"kind":"GROUP","key":"g1","label":"Box","children":[
                {"kind":"NOTE","key":"n2","title":"Two","markdown":"Inside"}
            ]}
        ]}"""
    }
}
