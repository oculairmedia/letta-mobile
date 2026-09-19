package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A shape's label moves and resizes with it, and goes when it goes. */
class CanvasShapeLabelsTest {

    private fun rect(id: String, bounds: Rect) = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(bounds.left, bounds.top), Offset(bounds.right, bounds.bottom)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
    )

    private fun label(id: String, frame: com.letta.mobile.data.canvas.CanvasDocumentFrame?) =
        CanvasSceneDocument(id = id, json = "", frame = frame, color = PLAIN_TEXT_COLOR)

    @Test
    fun labelSitsInsideItsShape() {
        val frame = CanvasShapeLabels.frameFor(Rect(0f, 0f, 200f, 100f))
        assertTrue(frame.x > 0f && frame.y > 0f, "the label is inset from the stroke, got $frame")
        assertTrue(frame.width < 200f && frame.height < 100f)
        assertEquals(100f, frame.x + frame.width / 2f, absoluteTolerance = 0.01f)
        assertEquals(50f, frame.y + frame.height / 2f, absoluteTolerance = 0.01f)
    }

    @Test
    fun labelIsReFramedWhenItsShapeMoves() {
        val shape = rect("rect-1", Rect(300f, 400f, 300f + 200f, 400f + 100f))
        val stale = label(CanvasShapeLabels.labelIdOf("rect-1"), CanvasShapeLabels.frameFor(Rect(0f, 0f, 200f, 100f)))
        val work = CanvasShapeLabels.reconcile(listOf(shape), listOf(stale), owners = mapOf(stale.id to "rect-1"))
        assertEquals(CanvasShapeLabels.frameFor(shape.bounds()), work.moved[stale.id])
        assertTrue(work.orphaned.isEmpty())
    }

    @Test
    fun labelIsOrphanedWhenItsShapeIsGone() {
        val lonely = label(CanvasShapeLabels.labelIdOf("rect-gone"), CanvasShapeLabels.frameFor(Rect(0f, 0f, 10f, 10f)))
        val work = CanvasShapeLabels.reconcile(emptyList(), listOf(lonely), owners = mapOf(lonely.id to "rect-gone"))
        assertEquals(listOf(lonely.id), work.orphaned)
    }

    @Test
    fun anOrdinaryNoteIsNotALabel() {
        val note = label("note-1", null)
        val work = CanvasShapeLabels.reconcile(emptyList(), listOf(note), owners = emptyMap())
        assertTrue(work.orphaned.isEmpty(), "a note that is not a label must not be swept up")
        assertTrue(work.moved.isEmpty())
        assertNull(CanvasShapeLabels.shapeIdOf("note-1"))
    }

    @Test
    fun onlyClosedShapesTakeALabel() {
        val line = Element.Shape(
            id = "line-1",
            shapeType = ShapeType.LINE,
            points = listOf(Offset(0f, 0f), Offset(100f, 0f)),
            strokeColor = Color.Black,
            strokeWidth = 2f,
        )
        assertTrue(CanvasShapeLabels.canLabel(rect("rect-1", Rect(0f, 0f, 0f + 10f, 0f + 10f))))
        assertTrue(!CanvasShapeLabels.canLabel(line), "a line has no inside to write in")
    }

    @Test
    fun anUnownedDocumentNamedLikeALabelIsLeftAlone() {
        // The name is not the proof. A board can hold an ordinary note called `label-report`, and
        // reading ownership off the id deleted it the moment no shape called `report` existed.
        val report = label("label-report", CanvasShapeLabels.frameFor(Rect(0f, 0f, 200f, 100f)))
        val numbered = label("label-123", null)

        val work = CanvasShapeLabels.reconcile(emptyList(), listOf(report, numbered), owners = emptyMap())

        assertTrue(work.orphaned.isEmpty(), "unowned documents must survive, got ${work.orphaned}")
        assertTrue(work.moved.isEmpty(), "unowned documents must not be re-framed either")
    }

    @Test
    fun anUnownedDocumentIsLeftAloneEvenWhenItsNamesakeShapeExists() {
        // Fail safe toward preservation: a shape called `report` does not make someone else's
        // `label-report` into this feature's to move.
        val shape = rect("report", Rect(300f, 400f, 300f + 200f, 400f + 100f))
        val report = label("label-report", CanvasShapeLabels.frameFor(Rect(0f, 0f, 200f, 100f)))

        val work = CanvasShapeLabels.reconcile(listOf(shape), listOf(report), owners = emptyMap())

        assertTrue(work.moved.isEmpty())
        assertTrue(work.orphaned.isEmpty())
    }

    @Test
    fun anOwnedLabelIsManagedWhateverItIsCalled() {
        // The mirror of the above: ownership is recorded, so a label may be called anything.
        val shape = rect("rect-1", Rect(300f, 400f, 300f + 200f, 400f + 100f))
        val oddlyNamed = label("scratch-7", null)

        val work = CanvasShapeLabels.reconcile(listOf(shape), listOf(oddlyNamed), owners = mapOf("scratch-7" to "rect-1"))

        assertEquals(CanvasShapeLabels.frameFor(shape.bounds()), work.moved["scratch-7"])
    }
}
