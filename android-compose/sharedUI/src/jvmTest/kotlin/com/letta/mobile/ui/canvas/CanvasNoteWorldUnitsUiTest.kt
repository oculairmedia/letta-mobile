@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentWrite
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasNoteAutoFitFixtures.B
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Board content is sized in world units (letta-mobile-bglj6.17): a note renders to the same
 * world-space bounds at any display density and any system font scale, the C4 reserve covers it on
 * a phone's density as on a desktop's, typing still works, and a zoom recomposes no card.
 *
 * The densities are a desktop's (1) and a phone's (2.75, xxhdpi-ish); the font scales the default
 * and an enlarged accessibility setting (1.3). At the board's zoom 1 one world unit is one px, so a
 * card's bounds in the root ARE its world-space bounds.
 */
class CanvasNoteWorldUnitsUiTest {

    private data class Display(val density: Float, val fontScale: Float) {
        val asDensity: Density get() = Density(density, fontScale)
    }

    private val desktop = Display(1f, 1f)
    private val displays = listOf(desktop, Display(2.75f, 1f), Display(1f, 1.3f), Display(2.75f, 1.3f))

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(store = InMemoryCanvasDocumentStore(), options = CanvasCreateOptions(title = "World Units", initialSceneJson = ""))
    }

    private fun CanvasSession.put(note: WorldNote) = runBlocking {
        writeDocument(CanvasDocumentWrite(note.id, note.json, frame = note.frame, owner = note.owner, color = note.color))
    }

    /** The notes of [session] on a board at [display], as a host would show them. */
    private fun ComposeUiTest.showNotes(
        session: CanvasSession,
        display: Display,
        viewport: MutableState<Viewport> = mutableStateOf(Viewport()),
        active: MutableState<String?> = mutableStateOf(null),
        onToolbar: ((NoteToolbar?) -> Unit)? = null,
        probe: ((String) -> Unit)? = null,
    ) {
        setContent {
            CompositionLocalProvider(LocalDensity provides display.asDensity, LocalNoteCompositionProbe provides probe) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        val doc by session.document.collectAsState()
                        val documents = remember(doc) { session.documents() }
                        // Read here, as the workspace reads its state: every pan or zoom recomposes
                        // the host and calls the layer again, with callbacks that are new objects
                        // each time (they capture the viewport of that frame).
                        val vp = viewport.value
                        CanvasNotesLayer(
                            session = session,
                            documents = documents,
                            viewport = vp,
                            activeNoteId = active.value,
                            onActivate = { id -> if (vp.scale > 0f) active.value = id },
                            onPress = { id, _ -> if (vp.scale > 0f) active.value = id },
                            onToolbar = onToolbar,
                            onLiveFrame = { _, _ -> vp.hashCode() },
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.card(id: String): SemanticsNode = onNodeWithContentDescription("Note $id").fetchSemanticsNode()

    private fun SemanticsNode.unclipped(): Rect = Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))

    private fun ComposeUiTest.cardBounds(id: String): Rect = card(id).unclipped()

    private fun ComposeUiTest.fontScale(id: String): Float? = card(id).config.getOrNull(NoteFontScaleKey)

    /** Where the editor row holding [text] sits, relative to note [id]'s card. */
    private fun ComposeUiTest.lineInCard(id: String, text: String): Rect {
        val line = onAllNodes(hasText(text, substring = true) and hasSetTextAction()).fetchSemanticsNodes().first().unclipped()
        return line.translate(-cardBounds(id).topLeft)
    }

    /** A board of one of each kind of card: an AUTO note, an EXPLICIT note, a frameless note and a plain text block. */
    private fun board(session: CanvasSession) {
        session.put(WorldNote("auto", CanvasNoteAutoFitFixtures.mealPlan, CanvasDocumentFrame(40f, 40f, 320f, 4000f), CanvasGeometryOwner.AUTO))
        session.put(WorldNote("explicit", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(400f, 40f, 300f, 260f), CanvasGeometryOwner.EXPLICIT))
        session.put(WorldNote("frameless", CanvasNoteAutoFitFixtures.document(B("heading", "Errands", level = 2), B("paragraph", "Post office, then the bank."))))
        session.put(WorldNote("plain", CanvasNoteAutoFitFixtures.document(B("paragraph", "Plain words on the board, wrapping onto a second line")),
            CanvasDocumentFrame(740f, 40f, 260f, 400f), CanvasGeometryOwner.AUTO, color = PLAIN_TEXT_COLOR,))
    }

    private data class Rendered(val bounds: Map<String, Rect>, val scales: Map<String, Float?>, val lines: Map<String, Rect>)

    private val ids = listOf("auto", "explicit", "frameless", "plain")

    private fun render(display: Display): Rendered {
        var out: Rendered? = null
        runDesktopComposeUiTest(width = BOARD, height = BOARD) {
            val session = session()
            board(session)
            showNotes(session, display)
            out = Rendered(
                bounds = ids.associateWith { cardBounds(it) },
                scales = ids.associateWith { fontScale(it) },
                lines = mapOf(
                    "auto" to lineInCard("auto", "Friday dinner: tacos"),
                    "explicit" to lineInCard("explicit", "Shopping item 3"),
                    "frameless" to lineInCard("frameless", "Post office"),
                    "plain" to lineInCard("plain", "Plain words"),
                ),
            )
        }
        return assertNotNull(out)
    }

    @Test
    fun aNoteRendersToTheSameWorldBoundsAtEveryDensityAndFontScale() {
        val reference = render(desktop)
        // Sanity: the AUTO note really was fitted to its content, so its height is the text's.
        assertTrue(reference.bounds.getValue("auto").height < 4000f)
        displays.drop(1).forEach { display ->
            val rendered = render(display)
            ids.forEach { id ->
                assertRectEquals(reference.bounds.getValue(id), rendered.bounds.getValue(id), "$id card at $display")
                assertEquals(reference.scales[id], rendered.scales[id], "$id type scale at $display")
                assertRectEquals(reference.lines.getValue(id), rendered.lines.getValue(id), "$id text line at $display")
            }
        }
    }

    /**
     * The estimator gate at a phone's density and an enlarged font setting: the reserve C4 books
     * (in world units) covers every fixture as rendered there, at full type size, and the rendered
     * height is the desktop's to the unit, since nothing in a card depends on the display any more.
     */
    @Test
    fun theReserveCoversEveryFixtureAtAPhonesDensityAndFontScale() {
        val cases = CanvasNoteAutoFitFixtures.notes.filter { it.name != CanvasNoteAutoFitFixtures.CAP_CASE }
        fun heights(display: Display): List<Pair<Float, Float?>> {
            var out: List<Pair<Float, Float?>> = emptyList()
            runDesktopComposeUiTest(width = BOARD, height = BOARD) {
                val session = session()
                cases.forEachIndexed { i, case ->
                    val frame = CanvasDocumentFrame(20f + (i % 5) * 360f, 20f + (i / 5) * 620f, case.width, 4000f)
                    session.put(WorldNote("fx$i", case.documentJson, frame, CanvasGeometryOwner.AUTO))
                }
                showNotes(session, display)
                out = cases.indices.map { i -> cardBounds("fx$i").height to fontScale("fx$i") }
            }
            return out
        }
        val onDesktop = heights(desktop)
        listOf(Display(2.75f, 1f), Display(2.75f, 1.3f)).forEach { phone ->
            val onPhone = heights(phone)
            cases.forEachIndexed { i, case ->
                val (measured, scale) = onPhone[i]
                assertEquals(1f, scale, "${case.name} needed smaller type at $phone")
                assertTrue(measured <= case.reserve, "${case.name} at $phone: rendered $measured > reserve ${case.reserve}")
                assertEquals(onDesktop[i].first, measured, 0.5f, "${case.name}: $phone renders a different height from a desktop")
            }
            println(cases.indices.joinToString("\n") { "reserve-gate@$phone ${cases[it].name}: rendered=${onPhone[it].first} reserve=${cases[it].reserve}" })
        }
    }

    /**
     * Nested list items as cascade-editor and the compose compiler store them: a flat list whose
     * children carry `attributes.indentationLevel`. The editor indents each level, so the words wrap
     * sooner; the reserve reads the level too and still covers the rendered card, on a desktop and
     * at a phone's density.
     */
    @Test
    fun indentedListItemsAreCoveredByTheirReserve() {
        val item = "Apples and pears from the market stall by the station"
        fun block(id: String, type: String, level: Int): String {
            val attributes = if (level > 0) ""","attributes":{"indentationLevel":$level}""" else ""
            return """{"id":"$id","type":{"typeId":"$type"}$attributes,"content":{"kind":"text","version":1,"text":"$item","spans":[]}}"""
        }
        val levels = listOf(0, 1, 2, 3, 2, 1)
        val indented = """{"version":2,"blocks":[${levels.mapIndexed { i, l -> block("i$i", if (i % 2 == 0) "bullet_list" else "todo", l) }.joinToString(",")}]}"""
        val flat = """{"version":2,"blocks":[${levels.mapIndexed { i, _ -> block("f$i", if (i % 2 == 0) "bullet_list" else "todo", 0) }.joinToString(",")}]}"""
        val reserve = com.letta.mobile.data.canvas.compose.CanvasComposeReserve.reserveDocument(indented, 320f)
        assertTrue(reserve > com.letta.mobile.data.canvas.compose.CanvasComposeReserve.reserveDocument(flat, 320f), "the reserve reads the indentation")
        // Below the cap, or the gate would hold by clamping rather than by estimating.
        assertTrue(reserve < com.letta.mobile.data.canvas.compose.CanvasComposeReserve.MAX_RESERVE, "reserve $reserve is the cap")
        listOf(desktop, Display(2.75f, 1.3f)).forEach { display ->
            runDesktopComposeUiTest(width = BOARD, height = BOARD) {
                val session = session()
                session.put(WorldNote("indented", indented, CanvasDocumentFrame(40f, 40f, 320f, 4000f), CanvasGeometryOwner.AUTO))
                session.put(WorldNote("flat", flat, CanvasDocumentFrame(400f, 40f, 320f, 4000f), CanvasGeometryOwner.AUTO))
                showNotes(session, display)
                val rendered = cardBounds("indented").height
                println("reserve-gate@$display indented list: rendered=$rendered (flat ${cardBounds("flat").height}) reserve=$reserve")
                assertEquals(1f, fontScale("indented"))
                assertTrue(rendered <= reserve, "at $display the indented list renders $rendered > reserve $reserve")
            }
        }
    }

    /** Typing into an AUTO note on a phone-density board still works: caret, input, the hoisted toolbar, the write. */
    @Test
    fun typingIntoAnAutoNoteStillWorksInWorldUnits() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        session.put(WorldNote("n", CanvasNoteAutoFitFixtures.document(B("paragraph", "Pack")), CanvasDocumentFrame(80f, 80f, 320f, 400f), CanvasGeometryOwner.AUTO))
        val active = mutableStateOf<String?>(null)
        var toolbar: NoteToolbar? = null
        showNotes(session, Display(2.75f, 1.3f), active = active, onToolbar = { toolbar = it })

        val field = onAllNodes(hasText("Pack", substring = true) and hasSetTextAction()).onFirst()
        field.performClick()
        waitForIdle()
        assertEquals("n", active.value, "a tap on the note's text makes it the active note")
        assertNotNull(toolbar, "the active note hands its formatting bar to the host")

        onAllNodes(hasText("Pack", substring = true) and hasSetTextAction()).onFirst().performTextInput(" the tent")
        waitUntil(timeoutMillis = 5_000) { session.documents().single().json.contains("the tent") }
        assertTrue(onAllNodes(hasText("the tent", substring = true) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        // Still a fitted card: typing grows nothing past what the text needs.
        assertEquals(1f, fontScale("n"))
    }

    /**
     * A pan and zoom re-place and re-scale the cards in layout and draw only: no card composes
     * again, not even the active one (its selection chrome, which is sized by the zoom, is its own
     * scope), though the host calls the layer again on every frame with new callbacks.
     */
    @Test
    fun zoomingAndPanningRecomposesNoCard() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        board(session)
        val compositions = HashMap<String, Int>()
        val viewport = mutableStateOf(Viewport())
        val active = mutableStateOf<String?>("explicit")
        showNotes(session, Display(2.75f, 1f), viewport = viewport, active = active, probe = { id -> compositions[id] = (compositions[id] ?: 0) + 1 })
        val before = HashMap(compositions)
        val atOne = cardBounds("auto")
        assertTrue(before.keys.containsAll(ids), "every card composed once: $before")

        var scale = 1f
        repeat(FRAMES) { frame ->
            scale *= if (frame < FRAMES / 2) 1.04f else 0.95f
            viewport.value = Viewport(offset = Offset(frame * 3f, frame * -2f), scale = scale)
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
        assertEquals(before, compositions, "a zoom recomposed a card")

        // And the zoom was applied: the card is the same world rect, scaled and moved (bounds in the
        // root carry the graphics-layer scale; a node's own size does not).
        val vp = viewport.value
        val now = card("auto").boundsInRoot
        assertEquals(atOne.width * vp.scale, now.width, 1f)
        assertEquals(atOne.height * vp.scale, now.height, 1f)
        assertEquals(vp.worldToScreen(atOne.topLeft).x, now.left, 1.5f)
    }

    /** A picture for a reviewer: the board at a phone's density and an enlarged font setting looks like the desktop's. */
    @Test
    fun snapshotAtAPhonesDensity() = runDesktopComposeUiTest(width = 1500, height = 700) {
        val session = session()
        board(session)
        showNotes(session, Display(2.75f, 1.3f))
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/canvas-autofit-snapshots").apply { mkdirs() }.resolve("world-units-density-2.75-font-1.3.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    /**
     * A text element on the drawing (DrawBox) is laid out in world units too: its ink covers the
     * same world-space box at density 1 and 2.75. It was 2.75 times larger on a phone.
     */
    @Test
    fun aDrawnTextElementInksTheSameWorldBoxAtEveryDensity() {
        val element = Element.Text(
            id = "t",
            text = "World units",
            fontSize = 24f,
            color = Color.Black,
            topLeft = Offset(20f, 20f),
            wrapWidth = 300f,
            measuredHeight = 30f,
        )
        fun inked(display: Display): java.awt.Rectangle {
            var box: java.awt.Rectangle? = null
            runDesktopComposeUiTest(width = 400, height = 200) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides display.asDensity) {
                        DrawingPreview(elements = listOf(element), bgColor = Color.White)
                    }
                }
                waitForIdle()
                box = onRoot().captureToImage().toAwtImage().inkBounds()
            }
            return assertNotNull(box)
        }
        val reference = inked(desktop)
        assertTrue(reference.height in 12..40, "24-unit type inks $reference")
        listOf(Display(2.75f, 1f), Display(2.75f, 1.3f)).forEach { display ->
            val box = inked(display)
            assertTrue(kotlin.math.abs(box.width - reference.width) <= 2 && kotlin.math.abs(box.height - reference.height) <= 2, "at $display the text inks $box, on a desktop $reference")
        }
    }

    private fun BufferedImage.inkBounds(): java.awt.Rectangle? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = getRGB(x, y)
            val dark = ((rgb shr 16) and 0xff) < 128 && ((rgb shr 8) and 0xff) < 128 && (rgb and 0xff) < 128
            if (dark) {
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        return if (maxX < 0) null else java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect, what: String) {
        val close = listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .all { (a, b) -> kotlin.math.abs(a - b) <= 1f }
        assertTrue(close, "$what: $actual, expected $expected")
    }

    private companion object {
        const val BOARD = 2_400
        const val FRAMES = 24
    }
}

/** A note these tests write to the board: its document, and the frame, owner and colour it is written with. */
private data class WorldNote(
    val id: String,
    val json: String,
    val frame: CanvasDocumentFrame? = null,
    val owner: CanvasGeometryOwner? = null,
    val color: String? = null,
)
