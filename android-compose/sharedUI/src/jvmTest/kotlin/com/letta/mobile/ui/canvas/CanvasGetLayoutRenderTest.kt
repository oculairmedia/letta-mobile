@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasLayoutResult
import com.letta.mobile.data.canvas.CanvasLayoutRow
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSnap
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * canvas_get_layout on a real board (letta-mobile-i9yps.3). The host tools place the notes, the
 * shape and the arrows; the overlay is the layout the tool reported.
 *
 * Snapshots: sharedUI/build/canvas-compose-e2e/layout-phone.png, layout-desktop.png,
 * layout-phone-moved.png, layout-desktop-moved.png.
 */
class CanvasGetLayoutRenderTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relay = InMemoryCanvasRelayStore()
    private val registry = ExternalToolRegistry.hostTools(
        HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(relay, hostId = { "host-1" }), relay, InMemoryHostCanvasDirectory())),
    )
    private val canvasId = CanvasId.forConversation(CONVERSATION)
    private var cursor = 0L

    @Test
    fun overlayFramesMatchTheBoardAndArrowsStayOnSideMidpoints() {
        val path = Files.createTempDirectory("canvas-layout-render-")
        val session = runBlocking {
            publish(board())
            open(path).also { catchUp(it) }
        }
        val before = runBlocking { layout() }
        render(session, before, PHONE, "layout-phone.png")
        render(session, before, DESKTOP, "layout-desktop.png")
        assertEndpoints(session, PLAN)

        val moved = PLAN.copy(x = PLAN.x + 36f)
        runBlocking {
            publish(listOf(noteOp("n-plan", NoteCopy("Plan", "The shape of it", "#bbf7d0"), moved)))
            reaim("n-plan", moved)
            catchUp(session)
        }
        val after = runBlocking { layout() }
        render(session, after, PHONE, "layout-phone-moved.png")
        render(session, after, DESKTOP, "layout-desktop-moved.png")
        assertEndpoints(session, moved)
        assertEquals(listOf(moved.x.toInt(), moved.y.toInt(), moved.width.toInt(), moved.height.toInt()), after.rows.single { it.id == "n-plan" }.frame)
    }

    private fun render(session: CanvasSession, reported: CanvasLayoutResult, size: BoardSize, snapshot: String) {
        val documents = session.documents()
        val elements = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements
        val viewport = Viewport(offset = Offset.Zero, scale = 1f)
        runDesktopComposeUiTest(width = size.width, height = size.height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            DrawingPreview(elements = elements, bgColor = Color.White, viewport = viewport)
                            CanvasNotesLayer(session = session, documents = documents, viewport = viewport)
                            LayoutOverlay(reported.rows, viewport)
                        }
                    }
                }
            }
            waitForIdle()
            reported.rows.filter { it.kind == "note" }.forEach { row ->
                val frame = assertNotNull(row.frame, row.id)
                val node = onNodeWithContentDescription("Note ${row.id}").fetchSemanticsNode()
                assertEquals(frame[0].toFloat(), node.positionInRoot.x, 1f, "${row.id} left")
                assertEquals(frame[1].toFloat(), node.positionInRoot.y, 1f, "${row.id} top")
                assertEquals(frame[2].toFloat(), node.size.width.toFloat(), 1f, "${row.id} width")
                assertEquals(frame[3].toFloat(), node.size.height.toFloat(), 1.5f, "${row.id} height")
                val right = node.positionInRoot.x + node.size.width
                val bottom = node.positionInRoot.y + node.size.height
                assertTrue(node.positionInRoot.x >= -1f && node.positionInRoot.y >= -1f && right <= size.width + 1f && bottom <= size.height + 1f, "${row.id} clipped at $right,$bottom on ${size.width}x${size.height}")
            }
            val shape = elements.single { it.id == "shape-box" }.bounds()
            val shapeRow = reported.rows.single { it.id == "shape-box" }.frame!!
            assertEquals(shape.left, shapeRow[0].toFloat(), 1f)
            assertEquals(shape.top, shapeRow[1].toFloat(), 1f)
            assertEquals(shape.width, shapeRow[2].toFloat(), 1f)
            assertEquals(shape.height, shapeRow[3].toFloat(), 1f)
            assertTrue(shape.right <= size.width + 1f && shape.bottom <= size.height + 1f, "shape clipped")
            val file = File("build/canvas-compose-e2e").apply { mkdirs() }.resolve(snapshot)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 0L)
            println("canvas-layout snapshot: ${file.absolutePath} (${size.width}x${size.height})")
        }
    }

    /** Each arrow point sits on the side midpoint of the note or shape it names. */
    private fun assertEndpoints(session: CanvasSession, plan: CanvasDocumentFrame) {
        val documents = session.documents().associate { it.id to it.frame!! }
        val elements = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements
        val arrows = elements.filterIsInstance<Element.Shape>().filter { it.id.startsWith("a-") }
        val frames = documents + ("shape-box" to SHAPE)
        val planFrames = frames + ("n-plan" to plan)
        val expected = mapOf(
            "a-sort" to (("n-inbox" to "right") to ("n-triage" to "left")),
            "a-next" to (("n-triage" to "bottom") to ("n-plan" to "top")),
            "a-check" to (("n-plan" to "bottom") to ("n-review" to "top")),
            "a-file" to (("n-ship" to "right") to ("n-notes" to "left")),
            "a-box" to (("n-done" to "right") to ("shape-box" to "left")),
        )
        arrows.forEach { arrow ->
            val (start, end) = expected.getValue(arrow.id)
            assertOnSide(arrow.points.first(), planFrames.getValue(start.first), start.second, arrow.id)
            assertOnSide(arrow.points.last(), planFrames.getValue(end.first), end.second, arrow.id)
            val mid = Offset(
                (arrow.points.first().x + arrow.points.last().x) / 2f,
                (arrow.points.first().y + arrow.points.last().y) / 2f,
            )
            planFrames.forEach { (id, frame) ->
                val inside = mid.x > frame.x + 2f && mid.x < frame.x + frame.width - 2f &&
                    mid.y > frame.y + 2f && mid.y < frame.y + frame.height - 2f
                assertTrue(!inside, "${arrow.id} label midpoint sits inside $id")
            }
        }
    }

    private fun assertOnSide(point: Offset, frame: CanvasDocumentFrame, side: String, id: String) {
        val (x, y) = CanvasSnap.anchorOn(frame, side)
        assertEquals(x, point.x, 2f, "$id $side x")
        assertEquals(y, point.y, 2f, "$id $side y")
    }

    private suspend fun reaim(documentId: String, frame: CanvasDocumentFrame) {
        val scene = layoutScene()
        val bindings = CanvasOpProjector.arrowBindingsOf(scene)
        val drawing = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(scene))
        val updates = bindings.mapNotNull { (id, binding) ->
            val shape = drawing.elements.filterIsInstance<Element.Shape>().singleOrNull { it.id == id } ?: return@mapNotNull null
            val geometry = CanvasSnapping.follow(shape, binding, documentId, frame) ?: return@mapNotNull null
            updateArrow(shape, geometry.points)
        }
        if (updates.isNotEmpty()) publish(updates)
    }

    private suspend fun layoutScene(): String =
        json.parseToJsonElement(call(CanvasToolContract.GET_SCENE, JsonObject(emptyMap())).content()).jsonObject["scene_json"]!!.jsonPrimitiveContent()

    private suspend fun layout(): CanvasLayoutResult =
        json.decodeFromString(CanvasLayoutResult.serializer(), call(CanvasToolContract.GET_LAYOUT, JsonObject(emptyMap())).content())

    private suspend fun publish(ops: List<JsonObject>) {
        call(CanvasToolContract.APPLY_OPS, buildJsonObject { put("ops", JsonArray(ops)) }).content()
    }

    private suspend fun call(tool: String, input: JsonObject): ExternalToolResult =
        registry.invoke(tool, input, ExternalToolCaller(agentId = AGENT, conversationId = CONVERSATION))

    private suspend fun catchUp(session: CanvasSession) {
        relay.readAfter(CanvasRelayProtocol.conversationTopic(CONVERSATION), cursor).forEach { entry ->
            assertNotNull(session.applyRemote(entry.op, vouchedActor = entry.op.actorId), "relay ${entry.cursor}")
            cursor = entry.cursor
        }
    }

    private suspend fun open(path: java.nio.file.Path): CanvasSession {
        val notebooks = NotebookLocalStore(path, PEER)
        return CanvasSession.create(
            NotebookCanvasDocumentStore(notebooks),
            CanvasCreateOptions(canvasId = canvasId, conversationId = CONVERSATION, agentId = AGENT),
        )
    }

    private fun board(): List<JsonObject> {
        val notes = listOf(
            noteOp("n-inbox", NoteCopy("Inbox", "New mail", "#dbeafe"), noteFrame("n-inbox")),
            noteOp("n-triage", NoteCopy("Triage", "Sort it", "#fde68a"), noteFrame("n-triage")),
            noteOp("n-plan", NoteCopy("Plan", "The shape of it", "#bbf7d0"), PLAN),
            noteOp("n-design", NoteCopy("Design", "Screens", "#fecaca"), noteFrame("n-design")),
            noteOp("n-build", NoteCopy("Build", "The work", "#e9d5ff"), noteFrame("n-build")),
            noteOp("n-review", NoteCopy("Review", "Look twice", "#fed7aa"), noteFrame("n-review")),
            noteOp("n-ship", NoteCopy("Ship", "Ready", "#bae6fd"), noteFrame("n-ship")),
            noteOp("n-notes", NoteCopy("Notes", "Left over", "#fbcfe8"), noteFrame("n-notes")),
            noteOp("n-done", NoteCopy("Done", "Shipped", "#d9f99d"), noteFrame("n-done")),
        )
        val shape = element(
            "shape-box",
            shapeJson(
                ShapeEnds(from = "${SHAPE.x},${SHAPE.y}", to = "${SHAPE.x + SHAPE.width},${SHAPE.y + SHAPE.height}"),
                ShapePaint("RECTANGLE", "Milestone", "#334155ff", "#e2e8f0ff"),
            ),
        )
        return notes + shape + arrows()
    }

    private fun arrows(): List<JsonObject> = listOf(
        arrow("a-sort", ArrowLink(Anchor("n-inbox", "right"), Anchor("n-triage", "left"), "sort")),
        arrow("a-next", ArrowLink(Anchor("n-triage", "bottom"), Anchor("n-plan", "top"), "next")),
        arrow("a-check", ArrowLink(Anchor("n-plan", "bottom"), Anchor("n-review", "top"), "check")),
        arrow("a-file", ArrowLink(Anchor("n-ship", "right"), Anchor("n-notes", "left"), "file")),
        arrowToShape("a-box", ArrowLink(Anchor("n-done", "right"), Anchor("shape-box", "left"), "ship")),
    ).flatten()

    private fun arrow(id: String, link: ArrowLink): List<JsonObject> {
        val from = CanvasSnap.anchorOn(noteFrame(link.from.id), link.from.side)
        val to = CanvasSnap.anchorOn(noteFrame(link.to.id), link.to.side)
        return listOf(
            element(id, shapeJson(ShapeEnds("${from.first},${from.second}", "${to.first},${to.second}"), ARROW_PAINT.copy(label = link.label))),
            bind(id, link.from, link.to),
        )
    }

    private fun arrowToShape(id: String, link: ArrowLink): List<JsonObject> {
        val from = CanvasSnap.anchorOn(noteFrame(link.from.id), link.from.side)
        val to = CanvasSnap.anchorOn(SHAPE, link.to.side)
        return listOf(
            element(
                id,
                shapeJson(ShapeEnds("${from.first},${from.second}", "${to.first},${to.second}", endBinding = link.to.id), ARROW_PAINT.copy(label = link.label)),
            ),
            bind(id, link.from, to = null),
        )
    }

    private fun updateArrow(shape: Element.Shape, points: List<Offset>): JsonObject = element(
        shape.id,
        shapeJson(
            ShapeEnds("${points.first().x},${points.first().y}", "${points.last().x},${points.last().y}"),
            ARROW_PAINT.copy(label = shape.text),
        ),
        update = true,
    )

    private fun noteFrame(id: String): CanvasDocumentFrame = when (id) {
        "n-inbox" -> CanvasDocumentFrame(16f, 24f, 140f, 80f)
        "n-triage" -> CanvasDocumentFrame(240f, 24f, 150f, 80f)
        "n-plan" -> PLAN
        "n-design" -> CanvasDocumentFrame(16f, 300f, 140f, 90f)
        "n-build" -> CanvasDocumentFrame(250f, 290f, 130f, 100f)
        "n-review" -> CanvasDocumentFrame(103f, 440f, 170f, 80f)
        "n-ship" -> CanvasDocumentFrame(16f, 560f, 130f, 72f)
        "n-notes" -> CanvasDocumentFrame(230f, 550f, 150f, 90f)
        "n-done" -> CanvasDocumentFrame(16f, 690f, 140f, 80f)
        else -> error(id)
    }

    private fun noteOp(id: String, copy: NoteCopy, frame: CanvasDocumentFrame): JsonObject = buildJsonObject {
        put("type", "set_document")
        put("documentId", id)
        put("documentJson", NOTE_JSON.format(copy.body))
        put("title", copy.title)
        put("color", copy.color)
        putJsonObject("frame") {
            put("x", frame.x)
            put("y", frame.y)
            put("width", frame.width)
            put("height", frame.height)
        }
    }

    private fun element(id: String, element: JsonObject, update: Boolean = false): JsonObject = buildJsonObject {
        put("type", if (update) "update_element" else "add_element")
        put("elementId", id)
        put("elementJson", element)
    }

    private fun shapeJson(ends: ShapeEnds, paint: ShapePaint): JsonObject = buildJsonObject {
        put("type", "Shape")
        put("shapeType", paint.shapeType)
        put("points", buildJsonArray { add(JsonPrimitive(ends.from)); add(JsonPrimitive(ends.to)) })
        put("strokeColor", paint.stroke)
        put("strokeWidth", 2.0)
        put("text", paint.label)
        put("fontSize", 13.0)
        paint.fill?.let { put("fillColor", it) }
        ends.endBinding?.let { put("endBinding", it) }
    }

    private fun bind(id: String, from: Anchor?, to: Anchor?): JsonObject = buildJsonObject {
        put("type", "set_arrow_binding")
        put("elementId", id)
        putJsonObject("binding") {
            if (from != null) putJsonObject("start") { put("documentId", from.id); put("side", from.side) }
            if (to != null) putJsonObject("end") { put("documentId", to.id); put("side", to.side) }
        }
    }

    private fun ExternalToolResult.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as JsonPrimitive).contentOrNull ?: error("not a string")

    private data class ShapeEnds(val from: String, val to: String, val endBinding: String? = null)

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-layout"
        const val PEER = "layout-render-peer"
        const val NOTE_JSON =
            """{"version":2,"blocks":[{"id":"b1","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"%s","spans":[]}}]}"""
        val PLAN = CanvasDocumentFrame(98f, 150f, 180f, 100f)
        val SHAPE = CanvasDocumentFrame(240f, 700f, 140f, 70f)
        val PHONE = BoardSize(412, 915)
        val DESKTOP = BoardSize(1440, 900)
        val ARROW_PAINT = ShapePaint("ARROW", label = "", stroke = "#0f172aff", fill = null)
    }

    private data class BoardSize(val width: Int, val height: Int)
    private data class NoteCopy(val title: String, val body: String, val color: String)
    private data class Anchor(val id: String, val side: String)
    private data class ArrowLink(val from: Anchor, val to: Anchor, val label: String)
    private data class ShapePaint(val shapeType: String, val label: String, val stroke: String, val fill: String?)
}

/** Magenta layout frames and cyan lines between the centres of bound rows. */
@androidx.compose.runtime.Composable
private fun LayoutOverlay(rows: List<CanvasLayoutRow>, viewport: Viewport) {
    val byId = rows.associateBy { it.id }
    Canvas(Modifier.fillMaxSize()) {
        drawReportedFrames(rows, viewport)
        drawReportedBindings(byId, viewport)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReportedFrames(rows: List<CanvasLayoutRow>, viewport: Viewport) {
    rows.forEach { row ->
        val frame = row.frame ?: return@forEach
        val topLeft = viewport.worldToScreen(Offset(frame[0].toFloat(), frame[1].toFloat()))
        val bottomRight = viewport.worldToScreen(Offset((frame[0] + frame[2]).toFloat(), (frame[1] + frame[3]).toFloat()))
        drawRect(Color.Magenta, topLeft, Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y), style = Stroke(1f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReportedBindings(
    byId: Map<String, CanvasLayoutRow>,
    viewport: Viewport,
) {
    byId.values.forEach { row ->
        val line = bindingLine(byId, row) ?: return@forEach
        drawLine(Color.Cyan, centre(viewport, line.from), centre(viewport, line.to), strokeWidth = 2f)
    }
}

private data class FramePair(val from: List<Int>, val to: List<Int>)

private fun bindingLine(byId: Map<String, CanvasLayoutRow>, row: CanvasLayoutRow): FramePair? {
    val binding = row.bindings ?: return null
    val from = binding.from?.let { byId[it]?.frame } ?: return null
    val to = binding.to?.let { byId[it]?.frame } ?: return null
    return FramePair(from, to)
}

private fun centre(viewport: Viewport, frame: List<Int>): Offset {
    val world = Offset(frame[0] + frame[2] / 2f, frame[1] + frame[3] / 2f)
    return viewport.worldToScreen(world)
}
