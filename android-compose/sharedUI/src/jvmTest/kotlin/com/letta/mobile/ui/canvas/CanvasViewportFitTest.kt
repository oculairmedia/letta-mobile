package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasViewportFitTest {
    private fun screen(fit: CanvasFit, world: Offset) = Offset(world.x * fit.scale + fit.offset.x, world.y * fit.scale + fit.offset.y)

    @Test
    fun fitScalesAndCentresTheContentInsideTheBoard() {
        val content = Rect(100f, 200f, 1700f, 1000f)
        val fit = CanvasViewportFit.fit(content, Size(800f, 600f))
        val topLeft = screen(fit, Offset(content.left, content.top))
        val bottomRight = screen(fit, Offset(content.right, content.bottom))
        assertTrue(topLeft.x >= 0f && topLeft.y >= 0f, "$topLeft")
        assertTrue(bottomRight.x <= 800f && bottomRight.y <= 600f, "$bottomRight")
        val centre = screen(fit, content.center)
        assertEquals(400f, centre.x, 0.01f)
        assertEquals(300f, centre.y, 0.01f)
    }

    @Test
    fun fitNeverZoomsBeyondTheLimits() {
        assertEquals(CanvasViewportFit.MAX_SCALE, CanvasViewportFit.fit(Rect(0f, 0f, 1f, 1f), Size(1000f, 1000f)).scale)
        assertEquals(CanvasViewportFit.MIN_SCALE, CanvasViewportFit.fit(Rect(0f, 0f, 100000f, 100000f), Size(100f, 100f)).scale)
    }

    @Test
    fun contentBoundsIncludeNoteFramesAndAreNullWhenEmpty() {
        assertNull(CanvasViewportFit.contentBounds(emptyList(), emptyList()))
        val note = CanvasSceneDocument(id = "n", json = "", frame = CanvasDocumentFrame(500f, 600f, 300f, 200f))
        assertEquals(Rect(500f, 600f, 800f, 800f), CanvasViewportFit.contentBounds(emptyList(), listOf(note)))
    }

    /**
     * The scene sharedLogic CanvasComposePlacementTest reads from JSON (letta-mobile-bglj6.9): placement
     * puts new artifacts against the same bounds zoom-to-fit shows, so the two are held to the same
     * numbers. Copied verbatim from that test's SHARED_FIXTURE_SCENE.
     */
    @Test
    fun contentBoundsAgreeWithComposePlacementOnTheSharedScene() {
        val elements = DrawingSerializer.deserialize(SHARED_FIXTURE_SCENE).elements
        val documents = CanvasOpProjector.documentsOf(SHARED_FIXTURE_SCENE)
        assertEquals(6, elements.size)
        assertEquals(2, documents.size)
        assertEquals(Rect(-20f, -80f, 1000f, 800f), CanvasViewportFit.contentBounds(elements, documents))
    }

    @Test
    fun panToShowCentresTheTargetAtTheCurrentZoom() {
        val viewport = Viewport(offset = Offset(-100f, 50f), scale = 2f)
        val target = Rect(300f, 300f, 400f, 360f)
        val pan = CanvasViewportFit.panToShow(target, viewport, IntSize(800, 600), centre = true)!!
        val centre = viewport.panBy(pan).worldToScreen(target.center)
        assertEquals(400f, centre.x, 0.01f)
        assertEquals(300f, centre.y, 0.01f)
    }

    @Test
    fun panToShowLeavesAVisibleTargetAloneUnlessAskedToCentre() {
        val target = Rect(10f, 10f, 110f, 110f)
        assertNull(CanvasViewportFit.panToShow(target, Viewport(), IntSize(800, 600), centre = false))
        assertTrue(CanvasViewportFit.panToShow(target, Viewport(), IntSize(800, 600), centre = true) != null)
        val offBoard = Rect(750f, 10f, 900f, 110f)
        assertTrue(CanvasViewportFit.panToShow(offBoard, Viewport(), IntSize(800, 600), centre = false) != null)
        assertNull(CanvasViewportFit.panToShow(target, Viewport(), IntSize.Zero, centre = true))
    }

    @Test
    fun panIntoBandLiftsANoteCoveredByTheKeyboardJustClearOfIt() {
        val band = Rect(0f, 80f, 400f, 380f)
        val note = Rect(50f, 500f, 250f, 700f)
        val pan = CanvasViewportFit.panIntoBand(note, Viewport(), band)!!
        assertEquals(Offset(0f, -320f), pan)
        assertNull(CanvasViewportFit.panIntoBand(note, Viewport().panBy(pan), band))
    }

    @Test
    fun panIntoBandShowsTheTopOfANoteTallerThanTheBand() {
        val band = Rect(0f, 80f, 400f, 380f)
        val tall = Rect(50f, 500f, 250f, 1500f)
        assertEquals(Offset(0f, -420f), CanvasViewportFit.panIntoBand(tall, Viewport(), band))
        assertNull(CanvasViewportFit.panIntoBand(Rect(10f, 100f, 90f, 200f), Viewport(), band))
    }

    private companion object {
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
