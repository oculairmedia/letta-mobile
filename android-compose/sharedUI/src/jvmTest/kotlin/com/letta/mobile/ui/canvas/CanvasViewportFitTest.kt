package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
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
}
