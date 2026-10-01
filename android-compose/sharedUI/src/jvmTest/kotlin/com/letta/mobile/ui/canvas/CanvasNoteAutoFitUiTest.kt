@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasNoteAutoFitFixtures.B
import io.ak1.drawbox.domain.model.Viewport
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Note auto-fit rendered for real on Skiko (canvas.compose C6, letta-mobile-bglj6.11; letta-mobile-8tlf9's
 * acceptance criteria). One world unit is one px at the board's zoom 1, which is what these tests
 * draw at, so a card's bounds in the root are its world-unit size.
 */
class CanvasNoteAutoFitUiTest {

    private fun session(store: CanvasDocumentStore = InMemoryCanvasDocumentStore()): CanvasSession = runBlocking {
        CanvasSession.create(store = store, options = CanvasCreateOptions(title = "Fit Board", initialSceneJson = ""))
    }

    private fun ComposeUiTest.showNotes(session: CanvasSession) {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val doc by session.document.collectAsState()
                    val documents = remember(doc) { session.documents() }
                    CanvasNotesLayer(session = session, documents = documents, viewport = Viewport())
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.card(id: String): SemanticsNode = onNodeWithContentDescription("Note $id").fetchSemanticsNode()

    /** A node's bounds in the root, NOT clipped by its ancestors: a clipped line shows past its card. */
    private fun SemanticsNode.unclipped(): Rect = Rect(positionInRoot, size.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) })

    private fun ComposeUiTest.cardBounds(id: String): Rect = card(id).unclipped()

    private fun ComposeUiTest.fontScale(id: String): Float? = card(id).config.getOrNull(NoteFontScaleKey)

    /** The text [text] is laid out, and wholly inside note [id]'s card: nothing of it is clipped. */
    private fun ComposeUiTest.assertReadable(id: String, text: String) {
        // The card's editor rows, as a screen reader meets them (the merged tree): the hidden
        // measuring copy auto-fit lays out is cleared from it and must not count.
        val nodes = onAllNodes(hasText(text, substring = true) and hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "'$text' is not laid out in note $id: the card cut it off")
        val card = cardBounds(id)
        val line = nodes.first().unclipped()
        assertTrue(line.bottom <= card.bottom + 0.5f, "'$text' ends at ${line.bottom}, below note $id's card at ${card.bottom}")
        assertTrue(line.top >= card.top - 0.5f, "'$text' at $line starts above note $id's card $card; ${nodes.size} nodes: ${nodes.map { it.unclipped().toString() + " " + it.config }}")
    }

    private suspend fun CanvasSession.opCount(): Int = opLog.getOps(canvasId).size

    private fun put(session: CanvasSession, id: String, json: String, frame: CanvasDocumentFrame? = null, owner: CanvasGeometryOwner? = null) = runBlocking {
        session.setDocument(id, json, frame = frame, owner = owner)
    }

    /** 8tlf9 (a)+(f): two agent notes written with no frame are readable at width 320, sized by content, and apart. */
    @Test
    fun framelessAgentNotesAreFullyReadableSizedByContentAndNeverOverlap() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        put(session, "shopping", CanvasNoteAutoFitFixtures.shoppingList)
        put(session, "meals", CanvasNoteAutoFitFixtures.mealPlan)
        showNotes(session)

        val shopping = cardBounds("shopping")
        val meals = cardBounds("meals")
        assertEquals(320f, shopping.width, 0.5f)
        assertEquals(320f, meals.width, 0.5f)
        assertReadable("shopping", "Shopping item 12")
        assertReadable("meals", "Friday dinner: tacos")
        assertTrue(shopping.height != meals.height, "heights follow content: $shopping vs $meals")
        assertTrue(!shopping.overlaps(meals), "$shopping overlaps $meals")
        // Still frameless: rendering placed and sized them without writing anything.
        assertTrue(session.documents().all { it.frame == null })
    }

    /** (b) An AUTO note booked taller than its content shrinks to the content, at full type size. */
    @Test
    fun autoNoteWithRoomToSpareShrinksToItsContent() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        put(session, "n", CanvasNoteAutoFitFixtures.document(B("paragraph", "Two short lines"), B("paragraph", "of text")), CanvasDocumentFrame(80f, 80f, 320f, 600f), CanvasGeometryOwner.AUTO)
        showNotes(session)

        val bounds = cardBounds("n")
        assertTrue(bounds.height < 600f, "card is ${bounds.height}, still the full booking")
        assertTrue(bounds.height >= CanvasNoteChrome.handleHeight.value, "card lost its chrome")
        assertEquals(1f, fontScale("n"))
        assertReadable("n", "of text")
    }

    /** (c) Content just over the booking: the type steps down, the card stays within the booking, nothing is cut. */
    @Test
    fun autoNoteJustOverItsBookingSetsSmallerTypeAndIsNotClipped() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        val frame = CanvasDocumentFrame(80f, 80f, 320f, 4000f)
        put(session, "n", CanvasNoteAutoFitFixtures.shoppingList, frame, CanvasGeometryOwner.AUTO)
        showNotes(session)
        val natural = cardBounds("n").height
        assertEquals(1f, fontScale("n"))

        // Book a little less than the content takes at full size.
        val booked = natural - 12f
        put(session, "n", CanvasNoteAutoFitFixtures.shoppingList, frame.copy(height = booked), CanvasGeometryOwner.AUTO)
        waitForIdle()

        val scale = assertNotNull(fontScale("n"))
        assertTrue(scale < 1f && scale >= CanvasNoteAutoFit.FONT_FLOOR, "type scale $scale")
        assertTrue(cardBounds("n").height <= booked, "card ${cardBounds("n").height} over its booking $booked")
        assertReadable("n", "Shopping item 12")
    }

    /** (c) Content far over the booking: type at the floor, then the card grows; an AUTO note is never clipped. */
    @Test
    fun autoNoteFarOverItsBookingGrowsAtTheFontFloor() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        put(session, "n", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(80f, 80f, 320f, 150f), CanvasGeometryOwner.AUTO)
        showNotes(session)

        assertEquals(CanvasNoteAutoFit.FONT_FLOOR, fontScale("n"))
        assertTrue(cardBounds("n").height > 150f, "card stayed at its booking and clipped")
        assertReadable("n", "Shopping item 12")
        // The growth is visual: the booking in the scene is what the compose layer wrote.
        assertEquals(150f, session.documents().single().frame?.height)
    }

    /** (d) An EXPLICIT frame is honoured verbatim, short or long content alike. */
    @Test
    fun explicitFramesAreDrawnVerbatim() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        put(session, "short", CanvasNoteAutoFitFixtures.document(B("paragraph", "Hi")), CanvasDocumentFrame(80f, 80f, 300f, 400f), CanvasGeometryOwner.EXPLICIT)
        put(session, "long", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(500f, 80f, 480f, 150f), CanvasGeometryOwner.EXPLICIT)
        showNotes(session)

        assertEquals(Rect(80f, 80f, 380f, 480f), cardBounds("short").round())
        assertEquals(Rect(500f, 80f, 980f, 230f), cardBounds("long").round())
        assertNull(fontScale("short"))
        assertNull(fontScale("long"))
        // The readability check tells a clipped card apart: this one is too short for its list.
        assertTrue(runCatching { assertReadable("long", "Shopping item 12") }.isFailure, "a 150-high card cannot show 12 items")
    }

    /** (e) A person's resize below the content survives a reload; auto-fit never fights a stored USER frame. */
    @Test
    fun aHumanResizeIsKeptAcrossAReload() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val store = InMemoryCanvasDocumentStore()
        val first = session(store)
        put(first, "n", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(80f, 80f, 320f, 900f), CanvasGeometryOwner.AUTO)
        runBlocking { first.moveDocument("n", CanvasDocumentFrame(80f, 80f, 320f, 160f)) }
        assertEquals(CanvasGeometryOwner.USER, first.documents().single().owner)

        val reloaded = assertNotNull(runBlocking { CanvasSession.open(store, first.canvasId, CanvasConversationOptions()) })
        assertEquals(CanvasGeometryOwner.USER, reloaded.documents().single().owner)
        showNotes(reloaded)

        assertEquals(160f, cardBounds("n").height, 0.5f)
        assertNull(fontScale("n"), "a USER note is not auto-fitted")
    }

    /**
     * A person dragging a fitted card takes it over at the size it was SHOWN at: the move writes
     * USER with the visible height, not the booking under it, so the card does not jump.
     */
    @Test
    fun movingAFittedCardKeepsTheSizeItWasShownAt() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        put(session, "n", CanvasNoteAutoFitFixtures.document(B("paragraph", "A short note")), CanvasDocumentFrame(80f, 80f, 320f, 600f), CanvasGeometryOwner.AUTO)
        showNotes(session)
        val shown = cardBounds("n").height
        assertTrue(shown < 600f)

        onNodeWithContentDescription("Move note").performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(40f, 30f))
            release()
        }
        waitUntil(timeoutMillis = 5000) { session.documents().single().owner == CanvasGeometryOwner.USER }
        val stored = session.documents().single().frame!!
        assertEquals(shown, stored.height, 0.5f)
        assertTrue(stored.x > 80f)
        waitForIdle()
        assertEquals(shown, cardBounds("n").height, 0.5f)
    }

    /** Measuring writes nothing: no op, no new revision, however long the cards sit there. */
    @Test
    fun renderingAutoNotesEmitsNoOps() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        put(session, "a", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(80f, 80f, 320f, 150f), CanvasGeometryOwner.AUTO)
        put(session, "b", CanvasNoteAutoFitFixtures.document(B("paragraph", "Hi")), CanvasDocumentFrame(500f, 80f, 320f, 600f), CanvasGeometryOwner.AUTO)
        put(session, "c", CanvasNoteAutoFitFixtures.mealPlan)
        val ops = runBlocking { session.opCount() }
        val revision = session.document.value?.revision
        val scene = session.sceneJsonOrEmpty()

        showNotes(session)
        // Past the editors' persist cadence, so a write-back would have happened by now.
        mainClock.advanceTimeBy(3_000)
        waitForIdle()

        assertEquals(ops, runBlocking { session.opCount() }, "rendering wrote ops")
        assertEquals(revision, session.document.value?.revision)
        assertEquals(scene, session.sceneJsonOrEmpty())
    }

    /**
     * The estimator gate (plan 3.4): every fixture's reserve from sharedLogic CanvasComposeReserve
     * covers the height the card really renders at on Skiko, at full type size. The cap case is
     * booked short on purpose (it grows instead) and is left out.
     */
    @Test
    fun theReserveCoversTheRenderedHeightOfEveryFixture() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = session()
        val cases = CanvasNoteAutoFitFixtures.notes.filter { it.name != CanvasNoteAutoFitFixtures.CAP_CASE }
        cases.forEachIndexed { i, case ->
            val frame = CanvasDocumentFrame(20f + (i % 5) * 360f, 20f + (i / 5) * 620f, case.width, 4000f)
            put(session, "fx$i", case.documentJson, frame, CanvasGeometryOwner.AUTO)
        }
        showNotes(session)

        val report = cases.mapIndexed { i, case ->
            val measured = cardBounds("fx$i").height
            Triple(case, measured, fontScale("fx$i"))
        }
        report.forEach { (case, measured, scale) ->
            assertEquals(1f, scale, "${case.name} needed smaller type")
            assertTrue(measured <= case.reserve, "${case.name}: rendered $measured > reserve ${case.reserve}")
        }
        println(report.joinToString("\n") { (case, measured, _) -> "reserve-gate ${case.name}: rendered=$measured reserve=${case.reserve}" })
    }

    /** A picture of the fits for a reviewer: shrunk, smaller type, grown at the floor, EXPLICIT, frameless. */
    @Test
    fun snapshotOfFittedNotes() = runDesktopComposeUiTest(width = 1500, height = 1100) {
        val session = session()
        put(session, "shrunk", CanvasNoteAutoFitFixtures.document(B("heading", "Weekend", level = 2), B("paragraph", "A booking far taller than this.")), CanvasDocumentFrame(20f, 20f, 320f, 600f), CanvasGeometryOwner.AUTO)
        put(session, "grown", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(360f, 20f, 320f, 200f), CanvasGeometryOwner.AUTO)
        put(session, "explicit", CanvasNoteAutoFitFixtures.shoppingList, CanvasDocumentFrame(700f, 20f, 320f, 200f), CanvasGeometryOwner.EXPLICIT)
        put(session, "frameless", CanvasNoteAutoFitFixtures.mealPlan)
        showNotes(session)
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/canvas-autofit-snapshots").apply { mkdirs() }.resolve("auto-fit.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    private fun Rect.round(): Rect = Rect(Math.round(left).toFloat(), Math.round(top).toFloat(), Math.round(right).toFloat(), Math.round(bottom).toFloat())

    private companion object {
        const val BOARD = 2_400
    }
}
