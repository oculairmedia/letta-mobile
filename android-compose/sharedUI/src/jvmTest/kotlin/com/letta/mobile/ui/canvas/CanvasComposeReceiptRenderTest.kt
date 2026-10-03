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
import androidx.compose.ui.geometry.Rect
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
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeReceiptItem
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Viewport
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Receipt frames on a real board (letta-mobile-i9yps.2). The agent's canvas_compose payload is
 * published through the host tool, then drawn at phone and desktop sizes with each reported frame
 * stroked in magenta. Compose does not create arrows, so there is no binding overlay.
 */
class CanvasComposeReceiptRenderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun overlayFramesMatchTheBoardAtPhoneAndDesktop() {
        val published = publish()
        try {
            assertNull(published.receipt.framesOmitted)
            assertReceiptFramesAreTheNotes(published.session, published.receipt)
            listOf(PHONE, DESKTOP).forEach { size ->
                val shot = render(published, size)
                assertFramesCoverTheNotes(published.receipt, shot)
            }
        } finally {
            published.notebooks.close()
        }
    }

    private fun publish(): Published {
        val relay = InMemoryCanvasRelayStore()
        val registry = ExternalToolRegistry.hostTools(
            HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(relay, hostId = { "host-1" }), relay, InMemoryHostCanvasDirectory())),
        )
        val canvasId = CanvasId.forConversation(CONVERSATION)
        val path = Files.createTempDirectory("canvas-receipt-frames-")
        val notebooks = NotebookLocalStore(path, PEER)
        val published = runBlocking {
            val input = json.parseToJsonElement(REQUEST).jsonObject
            val result = registry.invoke(
                CanvasToolContract.COMPOSE,
                input,
                ExternalToolCaller(agentId = AGENT, conversationId = CONVERSATION, toolCallId = CALL),
            )
            val answer = assertIs<ExternalToolResult.Success>(result, "compose refused").content
            val receipt = CanvasComposeContract.json.decodeFromString(ComposeReceipt.serializer(), answer)
            val session = CanvasSession.create(
                NotebookCanvasDocumentStore(notebooks),
                CanvasCreateOptions(canvasId = canvasId, conversationId = CONVERSATION, agentId = AGENT),
            )
            relay.readAfter(CanvasRelayProtocol.conversationTopic(CONVERSATION), 0L).forEach { entry ->
                check(session.applyRemote(entry.op, vouchedActor = entry.op.actorId) != null) { "relay ${entry.cursor}" }
            }
            session.load()
            Published(session, notebooks, receipt)
        }
        return published
    }

    private fun render(published: Published, size: BoardSize): Shot {
        val session = published.session
        val documents = session.documents()
        val elements = DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())).elements
        val viewport = fit(published.receipt.bounds!!, size)
        val frames = published.receipt.items.flatMap { item -> listOf(item) + item.children.orEmpty() }.mapNotNull { it.frame }
        var notes: Map<String, Rect> = emptyMap()
        runDesktopComposeUiTest(width = size.width, height = size.height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            DrawingPreview(elements = elements, bgColor = Color.White, viewport = viewport)
                            CanvasNotesLayer(session = session, documents = documents, viewport = viewport)
                            FrameOverlay(frames, viewport)
                        }
                    }
                }
            }
            waitForIdle()
            notes = documents.associate { document ->
                val node = onNodeWithContentDescription("Note ${document.id}").fetchSemanticsNode()
                document.id to Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))
            }
            val file = File("build/canvas-compose-e2e").apply { mkdirs() }.resolve(size.snapshot)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 0L, file.path)
            println("receipt-frames snapshot: ${file.absolutePath} (${size.width}x${size.height}, scale ${viewport.scale})")
        }
        return Shot(viewport, notes, size)
    }

    private fun assertFramesCoverTheNotes(receipt: ComposeReceipt, shot: Shot) {
        pieces(receipt).forEach { item ->
            val screen = screenOf(shot.viewport, item.frame!!)
            assertInside(screen, shot.size, item.key)
            if (item.kind in NOTE_KINDS) {
                val drawn = shot.notes.getValue(item.boardId(receipt.artifactId))
                val frame = item.frame!!
                // The card's semantics box is its world size; graphicsLayer applies the zoom on
                // draw, so width stays in world units while the origin is on screen. Compose
                // books a slot (at least the reserve floor) and auto-fit draws the card shorter
                // inside it, so the reported frame is that slot: origin and width match the card,
                // and the card does not hang out of the slot.
                assertEquals(screen.left, drawn.left, 1.5f, "${item.key} left")
                assertEquals(screen.top, drawn.top, 1.5f, "${item.key} top")
                assertEquals(frame[2].toFloat(), drawn.width, 1.5f, "${item.key} width")
                assertTrue(drawn.height <= frame[3] + 2f, "${item.key} drew ${drawn.height} past the reserved ${frame[3]}")
            }
        }
    }

    private fun assertReceiptFramesAreTheNotes(session: CanvasSession, receipt: ComposeReceipt) {
        val documents = session.documents().associateBy { it.id }
        pieces(receipt).filter { it.kind in NOTE_KINDS }.forEach { item ->
            val frame = documents.getValue(item.boardId(receipt.artifactId)).frame!!
            assertEquals(listOf(frame.x, frame.y, frame.width, frame.height).map(::halfUp), item.frame, item.key)
        }
    }

    private fun halfUp(value: Float): Int = (if (value >= 0f) value + 0.5f else value - 0.5f).toInt()
    private fun fit(bounds: ComposeBounds, size: BoardSize): Viewport {
        val room = Size(size.width - 2f * MARGIN, size.height - 2f * MARGIN)
        val scale = min(room.width / bounds.width, room.height / bounds.height).coerceIn(Viewport.MIN_SCALE, 1f)
        val left = (size.width - bounds.width * scale) / 2f
        val top = (size.height - bounds.height * scale) / 2f
        return Viewport(offset = Offset(left - bounds.x * scale, top - bounds.y * scale), scale = scale)
    }

    private fun screenOf(viewport: Viewport, frame: List<Int>): Rect {
        val origin = viewport.worldToScreen(Offset(frame[0].toFloat(), frame[1].toFloat()))
        val far = viewport.worldToScreen(Offset(frame[0] + frame[2].toFloat(), frame[1] + frame[3].toFloat()))
        return Rect(origin, far)
    }

    private fun assertInside(rect: Rect, size: BoardSize, what: String) {
        assertTrue(rect.left >= -1f && rect.top >= -1f, "$what starts outside the ${size.snapshot} window: $rect")
        assertTrue(rect.right <= size.width + 1f && rect.bottom <= size.height + 1f, "$what ends outside the ${size.snapshot} window: $rect")
    }

    private fun pieces(receipt: ComposeReceipt): List<ComposeReceiptItem> =
        receipt.items.flatMap { item -> listOf(item) + item.children.orEmpty() }

    private data class Published(val session: CanvasSession, val notebooks: NotebookLocalStore, val receipt: ComposeReceipt)

    private data class BoardSize(val width: Int, val height: Int, val snapshot: String)

    private data class Shot(val viewport: Viewport, val notes: Map<String, Rect>, val size: BoardSize)

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-receipt-frames"
        const val PEER = "receipt-frames-peer"
        const val CALL = "toolu_01ReceiptFrames"
        const val MARGIN = 16f
        val PHONE = BoardSize(412, 915, "receipt-frames-phone.png")
        val DESKTOP = BoardSize(1440, 900, "receipt-frames-desktop.png")
        val NOTE_KINDS = setOf(ComposeKind.NOTE, ComposeKind.CHECKLIST, ComposeKind.CARD)

        /**
         * Nine notes and one labelled group, the shape. Three columns, so the board is wider
         * than a phone and has to scale to fit.
         */
        const val REQUEST = """{"artifact_id":"diagram","title":"Launch","items":[
            {"kind":"NOTE","key":"inbox","title":"Inbox","color":"cyan","markdown":"New mail lands here"},
            {"kind":"NOTE","key":"triage","title":"Triage","color":"yellow","markdown":"Sort what matters"},
            {"kind":"NOTE","key":"plan","title":"Plan","color":"green","markdown":"The shape of the week"},
            {"kind":"NOTE","key":"design","title":"Design","color":"red","markdown":"Sketches before code"},
            {"kind":"NOTE","key":"build","title":"Build","color":"purple","markdown":"Ship the slice"},
            {"kind":"NOTE","key":"review","title":"Review","color":"orange","markdown":"Read it twice"},
            {"kind":"GROUP","key":"mile","label":"Milestone","children":[
                {"kind":"NOTE","key":"gate","title":"Gate","color":"green","markdown":"Ready to leave"}
            ]},
            {"kind":"NOTE","key":"ship","title":"Ship","color":"cyan","markdown":"Out the door"},
            {"kind":"NOTE","key":"notes","title":"Notes","color":"yellow","markdown":"What we learned"}
        ]}"""
    }
}

/** Reported receipt frames, one magenta pixel, in the same camera as the board. */
@androidx.compose.runtime.Composable
private fun FrameOverlay(frames: List<List<Int>>, viewport: Viewport) {
    Canvas(Modifier.fillMaxSize()) {
        frames.forEach { frame ->
            val origin = viewport.worldToScreen(Offset(frame[0].toFloat(), frame[1].toFloat()))
            val far = viewport.worldToScreen(Offset(frame[0] + frame[2].toFloat(), frame[1] + frame[3].toFloat()))
            drawRect(Color.Magenta, origin, Size(far.x - origin.x, far.y - origin.y), style = Stroke(1f))
        }
    }
}
