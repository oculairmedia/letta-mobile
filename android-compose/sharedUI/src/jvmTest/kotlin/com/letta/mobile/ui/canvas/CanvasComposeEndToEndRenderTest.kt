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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipts
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaMessageSerializer
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The multi-card end-to-end gate on desktop (letta-mobile-bglj6.14; the 8tlf9 gate of the bglj6
 * review): the agent's mixed artifact (sharedLogic's `request-multi-card.json`) composed by the
 * Iroh host's canvas.compose next to a drawing and two notes, written as one batch, taken by an
 * app into its notebook and read back after a restart, then RENDERED (Skiko) as the board draws
 * it. Every composed card is fully shown inside the height compose reserved for it, at full type
 * size; no two cards overlap, nothing covers the person's drawing or notes, and everything is
 * inside the receipt's bounds. The receipt card's "Show on canvas" frames those bounds on the real
 * workspace. A second compose renders beside the first without overlap, and a retry writes
 * nothing. Snapshots of the composed board are written to `build/canvas-compose-e2e/`.
 *
 * The dispatcher leg (App Server request -> ExternalToolDispatcher) is internal to sharedLogic and
 * is run by its CanvasComposeMultiCardEndToEndTest on the same request; here the host tool is
 * called with the call id exactly as the dispatcher calls it.
 */
class CanvasComposeEndToEndRenderTest {
    private val request: String = File(REQUEST_FIXTURE).readText()
    private val json = Json { ignoreUnknownKeys = true }

    private val relay = InMemoryCanvasRelayStore()
    private val registry = ExternalToolRegistry.hostTools(
        HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(relay, hostId = { "host-1" }), relay, InMemoryHostCanvasDirectory())),
    )
    private val canvasId = CanvasId.forConversation(CONVERSATION)

    private suspend fun call(tool: String, input: String, toolCallId: String? = null): ExternalToolResult =
        registry.invoke(tool, json.parseToJsonElement(input).jsonObject, agentId = AGENT, conversationId = CONVERSATION, toolCallId = toolCallId)

    private suspend fun compose(input: String, toolCallId: String): Pair<ComposeReceipt, String> {
        val result = assertIs<ExternalToolResult.Success>(call(CanvasToolContract.COMPOSE, input, toolCallId), "refused")
        return CanvasComposeContract.json.decodeFromString(ComposeReceipt.serializer(), result.content) to result.content
    }

    /** A drawing and two notes a person made before the agent composed anything. */
    private suspend fun drawAndWriteFirst() {
        fun note(text: String) =
            """{"version":2,"blocks":[{"id":"b1","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"$text","spans":[]}}]}"""
        val ops = buildJsonArray {
            addJsonObject {
                put("type", "add_element"); put("elementId", "sketch")
                put("elementJson", """{"type":"Shape","shapeType":"CIRCLE","points":["80.0,80.0","560.0,400.0"],"strokeColor":"#1f2937ff","strokeWidth":3.0,"zIndex":0}""")
            }
            addJsonObject {
                put("type", "set_document"); put("documentId", "note-a"); put("documentJson", note("Ideas for May"))
                put("frame", buildJsonObject { put("x", 80); put("y", 440); put("width", 300); put("height", 200) })
            }
            addJsonObject {
                put("type", "set_document"); put("documentId", "note-b"); put("documentJson", note("Ask Ana about the flat"))
                put("frame", buildJsonObject { put("x", 420); put("y", 440); put("width", 300); put("height", 260) })
            }
        }
        assertIs<ExternalToolResult.Success>(call(CanvasToolContract.APPLY_OPS, buildJsonObject { put("ops", ops) }.toString()))
    }

    /** The relay log from [after] applied by an app, as its canvas client applies what the host relays. */
    private suspend fun takeRelay(session: CanvasSession, after: Long): Long {
        var cursor = after
        relay.readAfter(CanvasRelayProtocol.conversationTopic(CONVERSATION), after).forEach { entry ->
            assertNotNull(session.applyRemote(entry.op, vouchedActor = entry.op.actorId), "relay entry ${entry.cursor}")
            cursor = entry.cursor
        }
        return cursor
    }

    @Test
    fun theComposedBoardRendersCleanlyAndShowOnCanvasFramesIt() {
        val path = Files.createTempDirectory("canvas-compose-e2e-render-")
        val (first, firstAnswer) = runBlocking {
            drawAndWriteFirst()
            val composed = compose(request, CALL_1)
            // The app's notebook takes the log; the app restarts.
            NotebookLocalStore(path, PEER).use { notebooks ->
                val session = CanvasSession.create(NotebookCanvasDocumentStore(notebooks), CanvasCreateOptions(canvasId = canvasId, conversationId = CONVERSATION, agentId = AGENT))
                takeRelay(session, 0L)
            }
            composed
        }
        assertEquals("lisbon-trip", first.artifactId)

        NotebookLocalStore(path, PEER).use { notebooks ->
            val session = assertNotNull(runBlocking { CanvasSession.open(NotebookCanvasDocumentStore(notebooks), canvasId) })
            runBlocking { session.load() }

            // --- rendered at zoom 1: one world unit is one px, so card bounds are world bounds ---
            val rendered = renderBoard(session, "multi-card-board.png")
            assertComposedCardsFit(session, first, rendered)

            // --- the chat: one receipt card, and Show on canvas frames its bounds --------------
            val card = receiptCard(CALL_1, request, firstAnswer)
            assertEquals(CanvasArtifactStatus.Published, card.status)
            assertEquals(first.bounds, card.bounds)
            assertShowOnCanvasFrames(session, card)

            // --- a second compose renders beside the first ---------------------------------------
            val second = runBlocking {
                val cursor = relay.readAfter(CanvasRelayProtocol.conversationTopic(CONVERSATION), 0L).last().cursor
                val (receipt, _) = compose(SECOND_REQUEST, CALL_2)
                takeRelay(session, cursor)
                receipt
            }
            val both = renderBoard(session, "multi-card-board-two-artifacts.png")
            assertComposedCardsFit(session, first, both)
            assertComposedCardsFit(session, second, both)
            val firstCards = composedIds(session, first.artifactId).map(both::getValue)
            composedIds(session, second.artifactId).map(both::getValue).forEach { b ->
                firstCards.forEach { a -> assertTrue(!a.overlaps(b), "second artifact's $b over the first's $a") }
            }

            // --- a retry writes nothing ----------------------------------------------------------
            runBlocking {
                val logged = relay.readAfter(CanvasRelayProtocol.conversationTopic(CONVERSATION), 0L).size
                val (retried, _) = compose(request, CALL_1)
                assertEquals(listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING), retried.warnings)
                assertEquals(first.bounds, retried.bounds)
                assertEquals(logged, relay.readAfter(CanvasRelayProtocol.conversationTopic(CONVERSATION), 0L).size)
            }
        }
    }

    // --- rendering -----------------------------------------------------------------------------

    /**
     * The board at zoom 1 as the workspace layers it (the drawing under the notes), panned so the
     * whole board shows (compose may place above y 0: it top-aligns with what is there), every
     * card's bounds in world units by document id, and a snapshot of it under build/canvas-compose-e2e/.
     */
    private fun renderBoard(session: CanvasSession, snapshot: String): Map<String, Rect> {
        val documents = session.documents()
        val elements = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements
        val extent = assertNotNull(CanvasViewportFit.contentBounds(elements, documents))
        val viewport = io.ak1.drawbox.domain.model.Viewport(offset = Offset(MARGIN - extent.left, MARGIN - extent.top))
        val width = ceil(extent.width + 2 * MARGIN).toInt()
        val height = ceil(extent.height + 2 * MARGIN).toInt()
        var out: Map<String, Rect> = emptyMap()
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            DrawingPreview(elements = elements, bgColor = Color.White, viewport = viewport)
                            CanvasNotesLayer(session = session, documents = documents, viewport = viewport)
                        }
                    }
                }
            }
            waitForIdle()
            out = documents.associate { it.id to cardBounds(it.id).translate(-viewport.offset) }
            val scales = documents.associate { it.id to onNodeWithContentDescription("Note ${it.id}").fetchSemanticsNode().config.getOrNull(NoteFontScaleKey) }
            scales.forEach { (id, scale) -> if (id.startsWith("cmp-")) assertEquals(1f, scale, "$id was drawn with smaller type to fit") }
            val file = File("build/canvas-compose-e2e").apply { mkdirs() }.resolve(snapshot)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 0)
            println("canvas-compose-e2e snapshot: ${file.absolutePath} (${width}x$height)")
        }
        return out
    }

    private fun ComposeUiTest.cardBounds(id: String): Rect {
        val node = onNodeWithContentDescription("Note $id").fetchSemanticsNode()
        return Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }

    /**
     * Each card of [receipt] as rendered: where compose put it, fully shown within its reserved
     * height (the auto-fit never needed to grow it or shrink its type), over no other card and no
     * drawn content of the person's, inside the receipt's bounds, and inside its group's frame.
     */
    private fun assertComposedCardsFit(session: CanvasSession, receipt: ComposeReceipt, rendered: Map<String, Rect>) {
        val documents = session.documents().associateBy { it.id }
        val ids = composedIds(session, receipt.artifactId)
        assertEquals(receipt.items.flatMap { listOf(it) + it.children.orEmpty() }.count { it.kind in DOCUMENT_KINDS }, ids.size)
        val bounds = assertNotNull(receipt.bounds).rect()
        val others = rendered.filterKeys { it !in ids }
        // The person's drawing as DrawBox draws it (a circle spans its points' diagonal).
        val sketch = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty()))
            .elements.single { it.id == "sketch" }.bounds()
        ids.forEach { id ->
            val document = documents.getValue(id)
            assertEquals(CanvasGeometryOwner.AUTO, document.owner, id)
            val frame = document.frame!!
            val card = rendered.getValue(id)
            assertEquals(frame.x, card.left, 1f, "$id left")
            assertEquals(frame.y, card.top, 1f, "$id top")
            assertEquals(frame.width, card.width, 1f, "$id width")
            assertTrue(card.height <= frame.height + 0.5f, "$id rendered ${card.height} tall, more than the ${frame.height} reserved")
            println("canvas-compose-e2e ${receipt.artifactId} $id: rendered ${card.height} of ${frame.height} reserved")
            assertTrue(bounds.containsRect(card), "$id $card is outside the receipt bounds $bounds")
            assertTrue(!card.overlaps(sketch), "$id $card covers the person's drawing")
            others.forEach { (other, rect) -> assertTrue(!card.overlaps(rect), "$id $card overlaps $other $rect") }
            ids.filter { it != id }.forEach { other -> assertTrue(!card.overlaps(rendered.getValue(other)), "$id overlaps $other") }
        }
        // A group's cards sit inside its frame.
        val elements = (json.parseToJsonElement(session.sceneJsonOrEmpty()).jsonObject["elements"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        receipt.items.filter { it.kind == ComposeKind.GROUP }.forEach { group ->
            val frame = elements.single { (it["id"] as JsonPrimitive).content == group.boardId(receipt.artifactId) }
            val (a, b) = (frame["points"] as kotlinx.serialization.json.JsonArray).map { p -> (p as JsonPrimitive).content.split(",").map(String::toFloat) }
            val box = Rect(a[0], a[1], b[0], b[1])
            group.children.orEmpty().filter { it.kind in DOCUMENT_KINDS }.forEach { child ->
                assertTrue(box.containsRect(rendered.getValue(child.boardId(receipt.artifactId))), "${child.key} is outside its group $box")
            }
        }
    }

    /** "Show on canvas" on the card, on the real workspace: the camera frames the receipt's bounds, at no more than 100%. */
    private fun assertShowOnCanvasFrames(session: CanvasSession, card: CanvasArtifactReceipt) {
        val intents = mutableListOf<ChatSurfaceIntent>()
        val actions = ChatCanvasActions(RecordingChatActions(), { intents += it }, "Could not share the canvas")
        actions.showArtifact(card)
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents)
        val target = assertNotNull(actions.camera.target)
        val bounds = assertNotNull(card.bounds).rect()
        assertEquals(bounds, target.bounds)

        runDesktopComposeUiTest(width = WORKSPACE_WIDTH, height = WORKSPACE_HEIGHT) {
            val controller = DrawBoxController(Reducer(UseCase()))
            setContent {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    CanvasWorkspace(controller = controller, session = session, cameraRequest = actions.camera)
                }
            }
            waitUntil(timeoutMillis = 10_000) { actions.camera.target == null }
            waitForIdle()
            val viewport = controller.state.value.viewport
            assertTrue(viewport.scale <= 1f + 1e-3f, "framed past 100%: ${viewport.scale}")
            val topLeft = viewport.worldToScreen(bounds.topLeft)
            val bottomRight = viewport.worldToScreen(bounds.bottomRight)
            val screen = Rect(topLeft, bottomRight)
            assertTrue(
                screen.left >= -1f && screen.top >= -1f && screen.right <= WORKSPACE_WIDTH + 1f && screen.bottom <= WORKSPACE_HEIGHT + 1f,
                "the artifact $bounds is at $screen on a ${WORKSPACE_WIDTH}x$WORKSPACE_HEIGHT board (scale ${viewport.scale})",
            )
            // Centred, as the fit does it.
            assertEquals(WORKSPACE_WIDTH / 2f, screen.center.x, 2f)
            assertEquals(WORKSPACE_HEIGHT / 2f, screen.center.y, 2f)
            // And the cards are on screen where the camera says.
            composedIds(session, card.artifactId).forEach { id ->
                val drawn = onNodeWithContentDescription("Note $id").fetchSemanticsNode().boundsInRoot
                assertTrue(drawn.left >= -1f && drawn.top >= -1f && drawn.right <= WORKSPACE_WIDTH + 1f && drawn.bottom <= WORKSPACE_HEIGHT + 1f, "$id is drawn at $drawn")
            }
            val file = File("build/canvas-compose-e2e").apply { mkdirs() }.resolve("show-on-canvas.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        }
    }

    private fun composedIds(session: CanvasSession, artifactId: String): List<String> =
        session.documents().filter { it.compose?.artifactId == artifactId }.map(CanvasSceneDocument::id)

    // --- the chat --------------------------------------------------------------------------

    /** The run's frames through the timeline reducer and the receipt projection: exactly one card, on the narration. */
    private fun receiptCard(callId: String, input: String, answer: String): CanvasArtifactReceipt {
        val run = "run-$callId"
        val returnFrame = buildJsonObject {
            put("message_type", "tool_return_message"); put("id", "$run-return"); put("run_id", run); put("seq_id", 2)
            put("status", "success"); put("tool_call_id", callId); put("tool_return", answer)
            putJsonArray("tool_returns") { add(buildJsonObject { put("tool_call_id", callId); put("status", "success"); put("tool_return", answer) }) }
        }
        val frames: List<LettaMessage> = listOf(
            UserMessage(id = "$run-user", contentRaw = JsonPrimitive("Plan the Lisbon trip on the board"), runId = run, otid = "$run-u"),
            ToolCallMessage(id = "$run-call", runId = run, seqId = 1, otid = "$run-c", toolCall = ToolCall(id = callId, name = CanvasToolContract.COMPOSE, arguments = input)),
            json.decodeFromJsonElement(LettaMessageSerializer, returnFrame),
            AssistantMessage(id = "$run-assistant", contentRaw = JsonPrimitive("It's on the board."), runId = run, seqId = 3, otid = "$run-a"),
        )
        var timeline = Timeline(conversationId = CONVERSATION)
        var pending = persistentMapOf<String, ToolReturnMessage>()
        frames.forEach { frame ->
            val next = reduceStreamFrame(TimelineReducerInput(prev = timeline, frame = frame, pendingToolReturnsByCallId = pending))
            timeline = next.next
            pending = next.updatedPendingToolReturnsByCallId
        }
        val attached = CanvasArtifactReceipts.attach(timeline.events)
        val narrating = timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(narrating)), attached.keys)
        return attached.values.single().single()
    }

    private fun ComposeBounds.rect() = Rect(x, y, x + width, y + height)

    private fun Rect.containsRect(o: Rect): Boolean =
        o.left >= left - 0.5f && o.top >= top - 0.5f && o.right <= right + 0.5f && o.bottom <= bottom + 0.5f

    private companion object {
        const val REQUEST_FIXTURE = "../sharedLogic/src/commonTest/resources/canvas/compose/v1/request-multi-card.json"
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-e2e"
        const val PEER = "e2e-render-peer"
        const val CALL_1 = "toolu_01LisbonTripCompose"
        const val CALL_2 = "toolu_01RestaurantsCompose"
        const val MARGIN = 80f
        const val WORKSPACE_WIDTH = 1280
        const val WORKSPACE_HEIGHT = 800
        val DOCUMENT_KINDS = setOf(ComposeKind.NOTE, ComposeKind.CHECKLIST, ComposeKind.CARD)

        val SECOND_REQUEST = """{"title":"Where to eat","items":[
            {"kind":"NOTE","title":"Alfama","markdown":"- Taberna Sal Grosso\n- Prado"},
            {"kind":"NOTE","title":"Baixa","markdown":"- Cervejaria Ramiro\n- *O Trevo* for a bifana"},
            {"kind":"CHECKLIST","title":"Book ahead","items":[{"text":"Ramiro, Friday"},{"text":"Prado, Saturday"}]}
        ]}"""
    }
}
