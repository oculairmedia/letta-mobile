package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
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
}
