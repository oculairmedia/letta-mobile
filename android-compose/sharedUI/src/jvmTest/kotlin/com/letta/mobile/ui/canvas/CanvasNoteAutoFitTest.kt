package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.letta.mobile.data.canvas.CanvasComposeProvenance
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasTextStyle
import com.letta.mobile.data.canvas.compose.CanvasComposePlacement
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve
import io.ak1.drawbox.domain.model.DrawingSerializer
import com.letta.mobile.data.canvas.compose.ReserveBlock
import com.letta.mobile.data.canvas.compose.ReserveBlockType
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.serialization.DocumentDecodeWarning
import io.github.linreal.cascade.editor.serialization.DocumentSchema
import io.github.linreal.cascade.editor.theme.CascadeEditorTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure half of note auto-fit (letta-mobile-bglj6.11): who fits, how a fit is chosen, and the constants it shares with the estimator. */
class CanvasNoteAutoFitTest {
    private val frame = CanvasDocumentFrame(0f, 0f, 320f, 400f)
    private val provenance = CanvasComposeProvenance(artifactId = "a", key = "i1", kind = "NOTE", catalog = "letta.canvas.compose", version = 1)

    @Test
    fun onlyAutoComposeMadeAndFramelessNotesAreFitted() {
        assertEquals(NoteSizing.FIT, noteSizing(CanvasSceneDocument("n", "", frame, owner = CanvasGeometryOwner.AUTO)))
        assertEquals(NoteSizing.FIT, noteSizing(CanvasSceneDocument("n", "", frame = null)))
        assertEquals(NoteSizing.FIT, noteSizing(CanvasSceneDocument("n", "", frame, compose = provenance)))
        assertEquals(NoteSizing.VERBATIM, noteSizing(CanvasSceneDocument("n", "", frame, owner = CanvasGeometryOwner.EXPLICIT)))
        assertEquals(NoteSizing.VERBATIM, noteSizing(CanvasSceneDocument("n", "", frame, owner = CanvasGeometryOwner.USER)))
        // A person's note from before owners existed keeps its frame.
        assertEquals(NoteSizing.VERBATIM, noteSizing(CanvasSceneDocument("n", "", frame)))
        // A person's move wins over compose provenance.
        assertEquals(NoteSizing.VERBATIM, noteSizing(CanvasSceneDocument("n", "", frame, owner = CanvasGeometryOwner.USER, compose = provenance)))
        // A shape's label belongs to its shape.
        assertEquals(NoteSizing.VERBATIM, noteSizing(CanvasSceneDocument("n", "", frame, owner = CanvasGeometryOwner.AUTO), isLabel = true))
    }

    @Test
    fun shortContentShrinksTheCardToTheContent() {
        val fit = CanvasNoteAutoFit.resolve(reserved = 400f, chrome = 28f) { 100f }
        assertEquals(NoteFit(128f, 1f), fit)
        assertTrue(!fit.grewPast(400f))
    }

    @Test
    fun longContentShrinksTheTypeFirstThenStopsAtTheFloor() {
        // The body is 400 at full size and scales with the type.
        val measured = mutableListOf<Float>()
        val fit = CanvasNoteAutoFit.resolve(reserved = 400f, chrome = 28f) { scale -> measured += scale; 400f * scale }
        assertEquals(listOf(1f, 0.9f), measured)
        assertEquals(NoteFit(388f, 0.9f), fit)
    }

    @Test
    fun contentTooLongEvenAtTheFloorGrowsPastTheReservation() {
        val fit = CanvasNoteAutoFit.resolve(reserved = 400f, chrome = 28f) { scale -> 1000f * scale }
        assertEquals(CanvasNoteAutoFit.FONT_FLOOR, fit.fontScale)
        assertEquals(828f, fit.height)
        assertTrue(fit.grewPast(400f), "an AUTO note is never clipped: it grows")
    }

    @Test
    fun aFixedScaleIsTheOnlyOneTried() {
        val measured = mutableListOf<Float>()
        val fit = CanvasNoteAutoFit.resolve(reserved = 100f, chrome = 0f, scales = listOf(0.9f)) { scale -> measured += scale; 500f }
        assertEquals(listOf(0.9f), measured)
        assertEquals(NoteFit(500f, 0.9f), fit)
    }

    @Test
    fun fitHeightsAreWholeUnits() {
        assertEquals(129f, CanvasNoteAutoFit.resolve(reserved = 400f, chrome = 28.2f) { 100.1f }.height)
    }

    @Test
    fun theFitScaleMultipliesTheDocumentsOwnScale() {
        assertEquals(null, CanvasNoteAutoFit.scaledStyle(null, 1f))
        assertEquals(CanvasTextStyle(fontScale = 0.8f), CanvasNoteAutoFit.scaledStyle(null, 0.8f))
        assertEquals(CanvasTextStyle(fontScale = 1.5f * 0.9f, align = "center"), CanvasNoteAutoFit.scaledStyle(CanvasTextStyle(fontScale = 1.5f, align = "center"), 0.9f))
    }

    @Test
    fun fontScalesRunLargestFirstDownToTheFloor() {
        val scales = CanvasNoteAutoFit.FONT_SCALES
        assertEquals(1f, scales.first())
        assertEquals(CanvasNoteAutoFit.FONT_FLOOR, scales.last())
        assertEquals(scales.sortedDescending(), scales)
    }

    /**
     * The estimator (sharedLogic CanvasComposeReserve) books the chrome the card draws, and at least
     * as much: it must never book less than what the renderer takes, or the reserve could be short
     * before any text is laid out. One source: the renderer's numbers are [CanvasNoteChrome].
     */
    @Test
    fun theEstimatorBooksAtLeastTheChromeTheCardDraws() {
        val drawnVertical = CanvasNoteChrome.handleHeight.value + 2 * CanvasNoteChrome.bodyVertical.value
        assertTrue(CanvasComposeReserve.CHROME_HANDLE + CanvasComposeReserve.CHROME_PADDING >= drawnVertical)
        assertTrue(CanvasComposeReserve.CHROME_HANDLE >= CanvasNoteChrome.handleHeight.value)

        // The estimator's inner width is the card's: its padding plus the editor's own block padding, each side.
        val editorPadding = CascadeEditorTheme.light().dimensions.blockHorizontalPadding.value
        assertTrue(CanvasComposeReserve.HORIZONTAL_PADDING >= CanvasNoteChrome.bodyStart.value + editorPadding)
        assertTrue(CanvasComposeReserve.HORIZONTAL_PADDING >= CanvasNoteChrome.bodyEnd.value + editorPadding)
        assertEquals(CanvasComposeReserve.INDENT.toFloat(), CascadeEditorTheme.light().dimensions.indentUnit.value)
    }

    /**
     * The fixtures are documents the editor really reads: no block falls back to "unsupported". The
     * heading fixtures once said `{"typeId":"heading","level":2}`, which the library shows as an
     * unsupported block and the estimator booked as a paragraph.
     */
    @Test
    fun fixturesAreDocumentsTheEditorReads() {
        val documents = CanvasNoteAutoFitFixtures.notes.map { it.name to it.documentJson } +
            listOf("shopping" to CanvasNoteAutoFitFixtures.shoppingList, "meals" to CanvasNoteAutoFitFixtures.mealPlan)
        documents.forEach { (name, json) ->
            val report = DocumentSchema.decodeFromStringWithReport(json)
            val unknown = report.warnings.filterIsInstance<DocumentDecodeWarning.UnknownBlockTypePreserved>()
            assertTrue(unknown.isEmpty(), "$name: $unknown")
        }
    }

    /** The estimator reads headings the way the library writes them: the level is in the typeId. */
    @Test
    fun theEstimatorReadsTheLibrarysHeadingEncoding() {
        val json = DocumentSchema.encodeToString(listOf(Block(BlockId("h"), BlockType.Heading(2), BlockContent.Text("Title"))))
        assertTrue("\"heading_2\"" in json, json)
        assertEquals(listOf(ReserveBlock(ReserveBlockType.HEADING, "Title", level = 2)), CanvasComposeReserve.blocksOf(json))
    }

    /** The copied fixtures still book what the estimator books (see [CanvasNoteAutoFitFixtures]). */
    @Test
    fun copiedFixturesMatchTheEstimator() {
        CanvasNoteAutoFitFixtures.notes.forEach { case ->
            assertEquals(case.reserve, CanvasComposeReserve.reserveDocument(case.documentJson, case.width), case.name)
        }
    }

    /**
     * The renderer places frameless notes against zoom-to-fit's bounds; the compiler places against
     * the engine's JSON reading of the same scene. On the shared fixture scene the two give the same
     * slots, so a peer and the host agree where a legacy note is.
     */
    @Test
    fun framelessSlotsFromViewportBoundsMatchTheEnginesJsonReading() {
        val elements = DrawingSerializer.deserialize(SHARED_FIXTURE_SCENE).elements
        val documents = CanvasOpProjector.documentsOf(SHARED_FIXTURE_SCENE)
        val rendered = framelessFramesOf(documents) { CanvasViewportFit.contentBounds(elements, documents) }
        val compiled = CanvasComposePlacement.placeFrameless(documents, CanvasComposePlacement.contentBounds(SHARED_FIXTURE_SCENE))
        assertEquals(compiled.keys, rendered.keys)
        assertTrue(rendered.isNotEmpty())
        compiled.forEach { (id, slot) ->
            assertEquals(CanvasDocumentFrame(slot.x, slot.y, slot.width, slot.height), rendered[id])
        }
    }

    @Test
    fun framelessNotesNeverOverlapAndFramedOnesAreLeftAlone() {
        val documents = listOf(
            CanvasSceneDocument("b", CanvasNoteAutoFitFixtures.shoppingList),
            CanvasSceneDocument("a", CanvasNoteAutoFitFixtures.mealPlan),
            CanvasSceneDocument("framed", "", CanvasDocumentFrame(0f, 0f, 300f, 200f)),
        )
        val frames = framelessFramesOf(documents) { CanvasViewportFit.contentBounds(emptyList(), documents) }
        assertEquals(setOf("a", "b"), frames.keys)
        val (a, b) = frames.getValue("a") to frames.getValue("b")
        val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
        assertTrue(!overlap, "$a overlaps $b")
        frames.values.forEach { assertEquals(CanvasComposePlacement.FRAMELESS_WIDTH, it.width) }
        assertEquals(emptyMap(), framelessFramesOf(documents.filter { it.frame != null }) { error("not needed") })
    }

    @Test
    fun aGroupMoveLandsFittedNotesAtTheHeightTheyWereShownAt() {
        val documents = listOf(
            CanvasSceneDocument("fit", "", CanvasDocumentFrame(0f, 0f, 320f, 600f), owner = CanvasGeometryOwner.AUTO),
            CanvasSceneDocument("plain", "", CanvasDocumentFrame(400f, 0f, 320f, 240f)),
        )
        val frames = CanvasWorkspaceSupport.buildMoveFrames(documents, setOf("fit", "plain"), Offset(10f, 20f), mapOf("fit" to 180f))
        assertEquals(CanvasDocumentFrame(10f, 20f, 320f, 180f), frames["fit"])
        assertEquals(CanvasDocumentFrame(410f, 20f, 320f, 240f), frames["plain"])
    }

    private companion object {
        /** sharedLogic CanvasComposePlacementTest.SHARED_FIXTURE_SCENE, as CanvasViewportFitTest copies it. */
        const val SHARED_FIXTURE_SCENE: String = """{"bgColor":"#ffffffff","elements":[""" +
            """{"id":"path","type":"Path","zIndex":0,"points":[],"strokeColor":"#000000ff","strokeWidth":2.0,"samples":["-20.0,10.0,2.0","40.0,30.0,2.0"]},""" +
            """{"id":"rect","type":"Shape","zIndex":1,"points":["0.0,200.0","50.0,220.0","120.0,260.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"RECTANGLE"},""" +
            """{"id":"circle","type":"Shape","zIndex":2,"points":["100.0,100.0","160.0,180.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"CIRCLE"},""" +
            """{"id":"arrow","type":"Shape","zIndex":3,"points":["300.0,0.0","500.0,0.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"ARROW","bend":"0.0,-80.0"},""" +
            """{"id":"text","type":"Text","zIndex":4,"points":[],"strokeColor":"#000000ff","strokeWidth":1.0,"text":"Hello","textTopLeft":"600.0,50.0","wrapWidth":200.0,"fontSize":20.0},""" +
            """{"id":"image","type":"Image","zIndex":5,"points":["900.0,400.0","1000.0,500.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"intrinsicWidth":100.0,"intrinsicHeight":100.0}""" +
            """],"_documents":[""" +
            """{"id":"framed","json":"{\"version\":2,\"blocks\":[]}","frame":{"x":500.0,"y":600.0,"width":300.0,"height":200.0}},""" +
            """{"id":"frameless","json":"{\"version\":2,\"blocks\":[]}"}""" +
            """]}"""
    }
}
