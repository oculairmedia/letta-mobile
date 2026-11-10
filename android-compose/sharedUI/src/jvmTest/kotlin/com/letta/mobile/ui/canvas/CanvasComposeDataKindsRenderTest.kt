@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.CanvasComposeIds
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.WorstCaseWrap
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.WorldDensity
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.text.FontRegistry
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The v2 data kinds as the board draws them (sharedLogic's `v2/request-data-kinds.json`, composed
 * by the Iroh host's canvas_compose and taken by an app from the relay), rendered with Skiko.
 *
 * The TABLE is a note whose table block is fully shown within the height compose reserved, at full
 * type size. Each STAT, PROGRESS and CHART is board elements: every one lies inside its item's
 * frame, and every text, laid out exactly as DrawBox lays it out (its font, size and wrap width),
 * fits in the height compose booked for it, so no two texts of an item overlap whatever the
 * platform's metrics. No two items overlap. "Show on canvas" then frames the whole dashboard on a
 * phone-width (360) and a desktop-width (1280) board. Snapshots are written to
 * `build/canvas-compose-e2e/`.
 */
class CanvasComposeDataKindsRenderTest {
    private val relay = InMemoryCanvasRelayStore()
    private val registry = ExternalToolRegistry.hostTools(
        HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(relay, hostId = { "host-1" }), relay, InMemoryHostCanvasDirectory())),
    )
    private val canvasId = CanvasId.forConversation(ConversationId(CONVERSATION))

    /** The fixture composed through the host tool, and a session that took the relay's log. */
    private fun composed(): Pair<ComposeReceipt, CanvasSession> = runBlocking {
        val input = CanvasComposeContract.json.parseToJsonElement(File(REQUEST_FIXTURE).readText()).jsonObject
        val caller = ExternalToolCaller(agentId = AGENT, conversationId = CONVERSATION, toolCallId = CALL)
        val result = assertIs<ExternalToolResult.Success>(registry.invoke(CanvasToolContract.COMPOSE, input, caller), "refused")
        val receipt = CanvasComposeContract.json.decodeFromString(ComposeReceipt.serializer(), result.content)
        val session = CanvasSession.create(
            InMemoryCanvasDocumentStore(),
            CanvasCreateOptions(canvasId = canvasId, conversationId = CONVERSATION, agentId = AGENT),
        )
        relay.readAfter(CanvasRelayProtocol.conversationTopic(ConversationId(CONVERSATION)), 0L).forEach { entry ->
            assertNotNull(session.applyRemote(entry.op, vouchedActor = entry.op.actorId), "relay entry ${entry.cursor}")
        }
        receipt to session
    }

    @Test
    fun everyDataKindRendersInsideItsSlotAndItsTextFits() {
        val (receipt, session) = composed()
        assertEquals("sprint-dashboard", receipt.artifactId)
        val drawn = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements
        var measurer: TextMeasurer? = null
        val documents = session.documents()
        val extent = assertNotNull(CanvasViewportFit.contentBounds(drawn, documents))
        val viewport = Viewport(offset = Offset(MARGIN - extent.left, MARGIN - extent.top))
        var tableCard = Rect.Zero
        runDesktopComposeUiTest(width = ceil(extent.width + 2 * MARGIN).toInt(), height = ceil(extent.height + 2 * MARGIN).toInt()) {
            setContent {
                measurer = rememberTextMeasurer()
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            DrawingPreview(elements = drawn, bgColor = Color.White, viewport = viewport)
                            CanvasNotesLayer(session = session, documents = documents, viewport = viewport)
                        }
                    }
                }
            }
            waitForIdle()
            val table = onNodeWithContentDescription("Note $TABLE_ID").fetchSemanticsNode()
            tableCard = Rect(table.positionInRoot - viewport.offset, Size(table.size.width.toFloat(), table.size.height.toFloat()))
            assertEquals(1f, table.config.getOrNull(NoteFontScaleKey), "the table was drawn with smaller type to fit")
            snapshot("data-kinds-board.png")
        }

        // The table: a note, fully shown inside its reservation.
        val document = documents.single { it.id == TABLE_ID }
        assertEquals(CanvasGeometryOwner.AUTO, document.owner)
        val frame = assertNotNull(document.frame)
        assertEquals(frame.width, tableCard.width, 1f)
        assertTrue(tableCard.height <= frame.height + 0.5f, "the table is ${tableCard.height} tall, over the ${frame.height} reserved")
        println("canvas-compose-e2e table: rendered ${tableCard.height} of ${frame.height} reserved")
        assertDrawnItemsFit(receipt, session, measurer)
    }

    /**
     * Each drawn item of [receipt]: its parts inside its frame, its texts within their booked
     * heights and apart, and no two items over each other.
     */
    private fun assertDrawnItemsFit(receipt: ComposeReceipt, session: CanvasSession, measurer: TextMeasurer?) {
        val sceneElements = elementsOf(session)
        val drawn = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements
        val drawnBounds = drawn.associate { it.id to it.bounds() }

        // Each drawn item: its parts inside its frame, its texts within their booked heights.
        val items = receipt.items.flatMap { listOf(it) + it.children.orEmpty() }
        val drawnItems = items.filter { it.kind in DRAWN_KINDS }
        assertEquals(6, drawnItems.size)
        drawnItems.forEach { item ->
            val id = item.boardIdIn(receipt)
            val box = drawnBounds.getValue(id)
            assertEquals(item.frame!!.map(Int::toFloat), listOf(box.left, box.top, box.width, box.height), "$id frame")
            val parts = sceneElements.filterKeys { it.startsWith(id + CanvasComposeIds.PART_SEPARATOR) }
            assertTrue(parts.size >= 3, "$id has ${parts.size} parts")
            parts.keys.forEach { part ->
                val partBox = drawnBounds.getValue(part)
                assertTrue(box.contains(partBox), "$part $partBox is outside $box")
            }
            val texts = parts.filterValues { it.string("type") == "Text" }
                .mapValues { (part, element) -> textBox(part, element, assertNotNull(measurer)) }
            texts.values.forEach { assertTrue(box.contains(it), "text $it is outside $box") }
            texts.entries.forEachIndexed { i, (a, boxA) ->
                texts.entries.drop(i + 1).forEach { (b, boxB) -> assertTrue(!boxA.overlaps(boxB), "$a $boxA overlaps $b $boxB") }
            }
        }

        // No two items overlap.
        val slots = items.filter { it.kind != ComposeKind.GROUP }.map { item ->
            val frame = item.frame!!.map(Int::toFloat)
            Rect(frame[0], frame[1], frame[0] + frame[2], frame[1] + frame[3])
        }
        slots.forEachIndexed { i, a -> slots.drop(i + 1).forEach { b -> assertTrue(!a.overlaps(b), "$a overlaps $b") } }
    }

    @Test
    fun showOnCanvasFramesTheDashboardOnANarrowAndAWideBoard() {
        val (receipt, session) = composed()
        val bounds = assertNotNull(receipt.bounds)
        listOf(PHONE to "data-kinds-narrow.png", DESKTOP to "data-kinds-wide.png").forEach { (size, snapshot) ->
            val intents = mutableListOf<ChatSurfaceIntent>()
            val actions = ChatCanvasActions(RecordingChatActions(), { intents += it }, "Could not share the canvas")
            actions.showArtifact(card(receipt, bounds))
            assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents)
            runDesktopComposeUiTest(width = size.width.toInt(), height = size.height.toInt()) {
                val controller = DrawBoxController(Reducer(UseCase()))
                setContent {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        CanvasWorkspace(controller = controller, session = session, cameraRequest = actions.camera)
                    }
                }
                waitUntil(timeoutMillis = 10_000) { actions.camera.target == null }
                waitForIdle()
                val view = controller.state.value.viewport
                assertTrue(view.scale <= 1f + 1e-3f, "framed past 100%: ${view.scale}")
                val topLeft = view.worldToScreen(Offset(bounds.x, bounds.y))
                val screen = Rect(topLeft, view.worldToScreen(Offset(bounds.x + bounds.width, bounds.y + bounds.height)))
                assertTrue(
                    screen.left >= -1f && screen.top >= -1f && screen.right <= size.width + 1f && screen.bottom <= size.height + 1f,
                    "the dashboard $bounds is at $screen on a $size board (scale ${view.scale})",
                )
                val tableNode = onNodeWithContentDescription("Note $TABLE_ID").fetchSemanticsNode()
                val table = tableNode.boundsInRoot
                val frame = assertNotNull(session.documents().single { it.id == TABLE_ID }.frame)
                val expected = view.worldToScreen(Offset(frame.x, frame.y))
                println("canvas-compose-e2e $snapshot table at ${tableNode.positionInRoot}, frame on screen at $expected")
                assertTrue(table.left >= -1f && table.right <= size.width + 1f, "the table is drawn at $table on a $size board")
                // The card is exactly where the camera puts its frame, on the phone board as on the
                // desktop one, though the card is wider than the phone's screen in world units.
                assertEquals(expected.x, tableNode.positionInRoot.x, 2f, "the table card's left on a $size board")
                assertEquals(expected.y, tableNode.positionInRoot.y, 2f, "the table card's top on a $size board")
                println("canvas-compose-e2e $snapshot: scale ${view.scale}")
                snapshot(snapshot)
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(name: String) {
        val file = File("build/canvas-compose-e2e").apply { mkdirs() }.resolve(name)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        assertTrue(file.length() > 0)
        println("canvas-compose-e2e snapshot: ${file.absolutePath}")
    }

    /**
     * A text part where DrawBox draws it: its top-left, its wrap width, and the height DrawBox's
     * own layout of it takes ([measurer], DrawBox's style, in world units). That height must be
     * within the one compose booked for it (its worst-case wrapped lines).
     */
    private fun textBox(part: String, element: JsonObject, measurer: TextMeasurer): Rect {
        val (x, y) = element.string("textTopLeft").split(",").map(String::toFloat)
        val width = element.number("wrapWidth")
        val font = element.number("fontSize")
        val text = element.string("text")
        val style = TextStyle(fontSize = font.sp, fontFamily = FontRegistry.resolve(element.string("fontFamilyKey")))
        val layout = measurer.measure(text, style, constraints = Constraints.fixedWidth(width.toInt()), density = WorldDensity)
        val measured = layout.size.height
        val lines = WorstCaseWrap(width.toDouble(), font.toDouble()).lines(text)
        val booked = ceil(lines * CanvasComposeReserve.LINE_HEIGHT_EM * font).toFloat()
        assertTrue(measured <= booked, "$part '$text' lays out $measured tall at $font in $width, over the $booked booked")
        return Rect(x, y, x + width, y + measured)
    }

    private fun elementsOf(session: CanvasSession): Map<String, JsonObject> =
        (CanvasComposeContract.json.parseToJsonElement(session.sceneJsonOrEmpty()).jsonObject["elements"] as JsonArray)
            .map { it.jsonObject }.associateBy { it.string("id") }

    private fun card(receipt: ComposeReceipt, bounds: ComposeBounds) = CanvasArtifactReceipt(
        artifactId = receipt.artifactId, canvasId = receipt.canvasId, revision = receipt.revision, status = CanvasArtifactStatus.Published,
        title = receipt.title, kinds = emptyList(), itemCount = receipt.items.size, bounds = bounds,
    )

    private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

    private fun JsonObject.number(key: String): Float = getValue(key).jsonPrimitive.content.toFloat()

    private fun Rect.contains(o: Rect): Boolean =
        o.left >= left - 0.5f && o.top >= top - 0.5f && o.right <= right + 0.5f && o.bottom <= bottom + 0.5f

    private companion object {
        const val REQUEST_FIXTURE = "../sharedLogic/src/commonTest/resources/canvas/compose/v2/request-data-kinds.json"
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-data-kinds"
        const val CALL = "toolu_01SprintDashboard"
        const val TABLE_ID = "cmp-sprint-dashboard-owners"
        const val MARGIN = 80f
        val PHONE = Size(360f, 780f)
        val DESKTOP = Size(1280f, 800f)
        val DRAWN_KINDS = setOf(ComposeKind.STAT, ComposeKind.PROGRESS, ComposeKind.CHART)
    }
}
