@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGetSceneResult
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasSceneDocument
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * connect on a real board (letta-mobile-i9yps.1). The host tool places the notes, the shape and
 * the arrows from the same JSON an agent would send. Magenta is each note and shape frame; cyan
 * joins the centres of each reported binding.
 *
 * Snapshots: sharedUI/build/canvas-compose-e2e/connect-phone.png, connect-desktop.png,
 * connect-phone-moved.png, connect-desktop-moved.png.
 *
 * Headless DrawingPreview does not run the live follow effect, so the "after" snapshot persists
 * [CanvasSnapping.follow] through update_element. The open board re-aims the same way from
 * followNoteConnectors.
 */
class CanvasConnectOpRenderTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relay = InMemoryCanvasRelayStore()
    private val registry = ExternalToolRegistry.hostTools(
        HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(relay, hostId = { "host-1" }), relay, InMemoryHostCanvasDirectory())),
    )
    private val canvasId = CanvasId.forConversation(CONVERSATION)
    private var cursor = 0L

    @Test
    fun overlayFramesMatchTheBoardAndArrowsStayOnSideMidpoints() {
        val path = Files.createTempDirectory("canvas-connect-render-")
        NotebookLocalStore(path, PEER).use { notebooks ->
            val session = runBlocking {
                publish(board())
                open(notebooks).also { catchUp(it) }
            }
            render(session, PHONE, "connect-phone.png")
            render(session, DESKTOP, "connect-desktop.png")
            assertEndpoints(session, PLAN)

            val moved = PLAN.copy(x = PLAN.x + 36f)
            runBlocking {
                publish(listOf(noteOp("n-plan", NoteCopy("Plan", "The shape of it", "#bbf7d0"), moved)))
                reaim("n-plan", moved)
                catchUp(session)
            }
            render(session, PHONE, "connect-phone-moved.png")
            render(session, DESKTOP, "connect-desktop-moved.png")
            assertEndpoints(session, moved)
        }
    }

    private fun render(session: CanvasSession, size: BoardSize, snapshot: String) {
        val documents = session.documents()
        val elements = drawingOf(session)
        val painted = paintedOf(session.sceneJsonOrEmpty(), documents, elements)
        assertEquals(LINKS.size, painted.links.size, "every connect should report a binding")
        val viewport = Viewport(offset = Offset.Zero, scale = 1f)
        runDesktopComposeUiTest(width = size.width, height = size.height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            DrawingPreview(elements = elements, bgColor = Color.White, viewport = viewport)
                            CanvasNotesLayer(session = session, documents = documents, viewport = viewport)
                            ConnectOverlay(painted, viewport)
                        }
                    }
                }
            }
            waitForIdle()
            painted.frames.filter { it.id != SHAPE_ID }.forEach { row -> assertNote(row, size) }
            assertShape(elements, painted, size)
            val file = File("build/canvas-compose-e2e").apply { mkdirs() }.resolve(snapshot)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 0L)
            println("canvas-connect snapshot: ${file.absolutePath} (${size.width}x${size.height})")
        }
    }

    private fun ComposeUiTest.assertNote(row: PaintedFrame, size: BoardSize) {
        val node = onNodeWithContentDescription("Note ${row.id}").fetchSemanticsNode()
        assertEquals(row.frame.x, node.positionInRoot.x, 1f, "${row.id} left")
        assertEquals(row.frame.y, node.positionInRoot.y, 1f, "${row.id} top")
        assertEquals(row.frame.width, node.size.width.toFloat(), 1f, "${row.id} width")
        assertEquals(row.frame.height, node.size.height.toFloat(), 1.5f, "${row.id} height")
        val right = node.positionInRoot.x + node.size.width
        val bottom = node.positionInRoot.y + node.size.height
        assertTrue(
            node.positionInRoot.x >= -1f && node.positionInRoot.y >= -1f && right <= size.width + 1f && bottom <= size.height + 1f,
            "${row.id} clipped",
        )
    }

    private fun assertShape(elements: List<Element>, painted: Painted, size: BoardSize) {
        val shape = elements.single { it.id == SHAPE_ID }.bounds()
        val frame = painted.frames.single { it.id == SHAPE_ID }.frame
        assertEquals(shape.left, frame.x, 1f)
        assertEquals(shape.top, frame.y, 1f)
        assertEquals(shape.width, frame.width, 1f)
        assertEquals(shape.height, frame.height, 1f)
        assertTrue(shape.right <= size.width + 1f && shape.bottom <= size.height + 1f, "shape clipped")
    }

    /** Each arrow point sits on the side midpoint of the note or shape it names. */
    private fun assertEndpoints(session: CanvasSession, plan: CanvasDocumentFrame) {
        val documents = session.documents().associate { it.id to it.frame!! }
        val frames = documents + (SHAPE_ID to SHAPE) + ("n-plan" to plan)
        drawingOf(session).filterIsInstance<Element.Shape>().filter { it.id.startsWith("a-") }.forEach { arrow ->
            val link = LINKS.getValue(arrow.id)
            assertOnSide(arrow.points.first(), frames.getValue(link.from.id), link.from.side, arrow.id)
            assertOnSide(arrow.points.last(), frames.getValue(link.to.id), link.to.side, arrow.id)
            assertEquals(link.label, arrow.text, arrow.id)
            assertLabelSitsInTheGap(arrow, frames)
        }
    }

    private fun assertLabelSitsInTheGap(arrow: Element.Shape, frames: Map<String, CanvasDocumentFrame>) {
        val mid = Offset((arrow.points.first().x + arrow.points.last().x) / 2f, (arrow.points.first().y + arrow.points.last().y) / 2f)
        frames.forEach { (id, frame) ->
            val inside = mid.x > frame.x + 2f && mid.x < frame.x + frame.width - 2f &&
                mid.y > frame.y + 2f && mid.y < frame.y + frame.height - 2f
            assertTrue(!inside, "${arrow.id} label midpoint sits inside $id")
        }
    }

    private fun assertOnSide(point: Offset, frame: CanvasDocumentFrame, side: String, id: String) {
        val (x, y) = CanvasSnap.anchorOn(frame, side)
        assertEquals(x, point.x, 2f, "$id $side x")
        assertEquals(y, point.y, 2f, "$id $side y")
    }

    private suspend fun reaim(documentId: String, frame: CanvasDocumentFrame) {
        val scene = sceneJson()
        val bindings = CanvasOpProjector.arrowBindingsOf(scene)
        val drawing = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(scene))
        val updates = bindings.mapNotNull { (id, binding) ->
            val shape = drawing.elements.filterIsInstance<Element.Shape>().singleOrNull { it.id == id } ?: return@mapNotNull null
            val geometry = CanvasSnapping.follow(shape, binding, documentId, frame) ?: return@mapNotNull null
            updateArrow(shape, geometry.points)
        }
        if (updates.isNotEmpty()) publish(updates)
    }

    private suspend fun sceneJson(): String =
        json.decodeFromString(CanvasGetSceneResult.serializer(), call(CanvasToolContract.GET_SCENE, JsonObject(emptyMap())).content()).sceneJson

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

    private suspend fun open(notebooks: NotebookLocalStore): CanvasSession = CanvasSession.create(
        NotebookCanvasDocumentStore(notebooks),
        CanvasCreateOptions(canvasId = canvasId, conversationId = CONVERSATION, agentId = AGENT),
    )

    private fun drawingOf(session: CanvasSession): List<Element> =
        DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements

    private fun board(): List<JsonObject> = notes() + shape() + connects()

    private fun notes(): List<JsonObject> = listOf(
        noteOp("n-inbox", NoteCopy("Inbox", "New mail", "#dbeafe"), frameOf("n-inbox")),
        noteOp("n-triage", NoteCopy("Triage", "Sort it", "#fde68a"), frameOf("n-triage")),
        noteOp("n-plan", NoteCopy("Plan", "The shape of it", "#bbf7d0"), PLAN),
        noteOp("n-design", NoteCopy("Design", "Screens", "#fecaca"), frameOf("n-design")),
        noteOp("n-build", NoteCopy("Build", "The work", "#e9d5ff"), frameOf("n-build")),
        noteOp("n-review", NoteCopy("Review", "Look twice", "#fed7aa"), frameOf("n-review")),
        noteOp("n-ship", NoteCopy("Ship", "Ready", "#bae6fd"), frameOf("n-ship")),
        noteOp("n-notes", NoteCopy("Notes", "Left over", "#fbcfe8"), frameOf("n-notes")),
        noteOp("n-done", NoteCopy("Done", "Shipped", "#d9f99d"), frameOf("n-done")),
    )

    private fun shape(): JsonObject = buildJsonObject {
        put("type", "add_element")
        put("elementId", SHAPE_ID)
        put("elementJson", shapeJson())
    }

    private fun shapeJson(): JsonObject = buildJsonObject {
        put("type", "Shape")
        put("shapeType", "RECTANGLE")
        put("points", buildJsonArray {
            add(JsonPrimitive("${SHAPE.x},${SHAPE.y}"))
            add(JsonPrimitive("${SHAPE.x + SHAPE.width},${SHAPE.y + SHAPE.height}"))
        })
        put("strokeColor", "#334155ff")
        put("strokeWidth", 2.0)
        put("fillColor", "#e2e8f0ff")
        put("text", "Milestone")
        put("fontSize", 13.0)
    }

    private fun connects(): List<JsonObject> = LINKS.map { (id, link) ->
        buildJsonObject {
            put("type", "connect")
            put("id", id)
            put("from", link.from.id)
            put("to", link.to.id)
            put("label", link.label)
        }
    }

    private fun updateArrow(shape: Element.Shape, points: List<Offset>): JsonObject = buildJsonObject {
        put("type", "update_element")
        put("elementId", shape.id)
        put("elementJson", arrowJson(shape, points))
    }

    private fun arrowJson(shape: Element.Shape, points: List<Offset>): JsonObject = buildJsonObject {
        put("type", "Shape")
        put("shapeType", "ARROW")
        put("points", buildJsonArray {
            add(JsonPrimitive(pointOf(points.first())))
            add(JsonPrimitive(pointOf(points.last())))
        })
        put("strokeColor", "#000000ff")
        put("strokeWidth", 2.0)
        put("text", shape.text)
        put("fontSize", shape.fontSize.toDouble())
    }

    private fun pointOf(at: Offset): String = "${at.x},${at.y}"

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

    private fun frameOf(id: String): CanvasDocumentFrame = when (id) {
        "n-inbox" -> CanvasDocumentFrame(16f, 24f, 140f, 80f)
        "n-triage" -> CanvasDocumentFrame(240f, 24f, 150f, 80f)
        "n-design" -> CanvasDocumentFrame(16f, 300f, 140f, 90f)
        "n-build" -> CanvasDocumentFrame(250f, 290f, 130f, 100f)
        "n-review" -> CanvasDocumentFrame(103f, 440f, 170f, 80f)
        "n-ship" -> CanvasDocumentFrame(16f, 560f, 130f, 72f)
        "n-notes" -> CanvasDocumentFrame(230f, 550f, 150f, 90f)
        "n-done" -> CanvasDocumentFrame(16f, 690f, 140f, 80f)
        else -> error(id)
    }

    private fun paintedOf(scene: String, documents: List<CanvasSceneDocument>, elements: List<Element>): Painted {
        val frames = documents.mapNotNull { doc -> doc.frame?.let { PaintedFrame(doc.id, it) } } +
            elements.filter { it.id == SHAPE_ID }.map { PaintedFrame(it.id, it.bounds().toFrame()) }
        val known = frames.associate { it.id to it.frame }
        val bindings = CanvasOpProjector.arrowBindingsOf(scene)
        val links = elements.filterIsInstance<Element.Shape>().mapNotNull { arrow -> linkOf(arrow, bindings[arrow.id], known) }
        return Painted(frames, links)
    }

    private fun linkOf(
        arrow: Element.Shape,
        binding: com.letta.mobile.data.canvas.CanvasArrowBinding?,
        known: Map<String, CanvasDocumentFrame>,
    ): PaintedLink? {
        val from = binding?.start?.documentId ?: arrow.startBinding ?: return null
        val to = binding?.end?.documentId ?: arrow.endBinding ?: return null
        if (from !in known || to !in known) return null
        return PaintedLink(from, to)
    }

    private fun ExternalToolResult.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun Rect.toFrame(): CanvasDocumentFrame = CanvasDocumentFrame(left, top, width, height)

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-connect"
        const val PEER = "connect-render-peer"
        const val SHAPE_ID = "shape-box"
        const val NOTE_JSON =
            """{"version":2,"blocks":[{"id":"b1","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"%s","spans":[]}}]}"""
        val PLAN = CanvasDocumentFrame(98f, 150f, 180f, 100f)
        val SHAPE = CanvasDocumentFrame(240f, 700f, 140f, 70f)
        val PHONE = BoardSize(412, 915)
        val DESKTOP = BoardSize(1440, 900)
        val LINKS = mapOf(
            "a-sort" to ArrowLink(Anchor("n-inbox", "right"), Anchor("n-triage", "left"), "sort"),
            "a-next" to ArrowLink(Anchor("n-triage", "bottom"), Anchor("n-plan", "top"), "next"),
            "a-check" to ArrowLink(Anchor("n-plan", "bottom"), Anchor("n-review", "top"), "check"),
            "a-file" to ArrowLink(Anchor("n-ship", "right"), Anchor("n-notes", "left"), "file"),
            "a-box" to ArrowLink(Anchor("n-done", "right"), Anchor("shape-box", "left"), "ship"),
        )
    }

    private data class BoardSize(val width: Int, val height: Int)
    private data class NoteCopy(val title: String, val body: String, val color: String)
    private data class Anchor(val id: String, val side: String)
    private data class ArrowLink(val from: Anchor, val to: Anchor, val label: String)
}

private data class PaintedFrame(val id: String, val frame: CanvasDocumentFrame)

private data class PaintedLink(val from: String, val to: String)

private data class Painted(val frames: List<PaintedFrame>, val links: List<PaintedLink>)

/** Magenta frames and cyan lines between the centres of bound notes and shapes. */
@Composable
private fun ConnectOverlay(painted: Painted, viewport: Viewport) {
    val byId = painted.frames.associate { it.id to it.frame }
    Canvas(Modifier.fillMaxSize()) {
        drawFrames(painted.frames, viewport)
        drawLinks(painted.links, byId, viewport)
    }
}

private fun DrawScope.drawFrames(frames: List<PaintedFrame>, viewport: Viewport) {
    frames.forEach { row ->
        val topLeft = viewport.worldToScreen(Offset(row.frame.x, row.frame.y))
        val bottomRight = viewport.worldToScreen(Offset(row.frame.x + row.frame.width, row.frame.y + row.frame.height))
        drawRect(Color.Magenta, topLeft, Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y), style = Stroke(1f))
    }
}

private fun DrawScope.drawLinks(links: List<PaintedLink>, byId: Map<String, CanvasDocumentFrame>, viewport: Viewport) {
    links.forEach { link ->
        val from = byId[link.from] ?: return@forEach
        val to = byId[link.to] ?: return@forEach
        drawLine(Color.Cyan, centre(viewport, from), centre(viewport, to), strokeWidth = 2f)
    }
}

private fun centre(viewport: Viewport, frame: CanvasDocumentFrame): Offset =
    viewport.worldToScreen(Offset(frame.x + frame.width / 2f, frame.y + frame.height / 2f))
