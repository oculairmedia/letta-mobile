package io.ak1.drawbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.topmostHit
import io.ak1.drawbox.domain.model.translate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Culling, change tracking and stroke caching behind DrawBox's cached static layer (letta-mobile-24zur.1). */
class RenderIndexTest {
    private fun box(id: String, x: Float, y: Float, z: Int, rotation: Float = 0f) = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, y), Offset(x + 100f, y + 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
        rotation = rotation,
    )

    private fun stroke(id: String, x: Float, y: Float, z: Int) = Element.Path(
        id = id,
        samples = (0 until 50).map { Element.PathSample(Offset(x + it * 4f, y + (it % 5) * 3f), 3f) },
        strokeColor = Color.Black,
        strokeWidth = 3f,
        alpha = 1f,
        zIndex = z,
    )

    /** A 100 x 100 grid of boxes 1000 units apart. */
    private val board: List<Element> = (0 until 10_000).map { i -> box("b$i", (i % 100) * 1000f, (i / 100) * 1000f, i) }

    @Test
    fun aViewOfTheBoardFindsOnlyWhatPaintsInItInPaintOrder() {
        val index = RenderIndex().apply { update(board) }
        val seen = index.query(Rect(1950f, 950f, 4050f, 2050f))
        assertEquals(listOf("b102", "b103", "b104", "b202", "b203", "b204"), seen.map { it.id })
    }

    @Test
    fun aViewWiderThanTheBoardStillAnswersEveryElement() {
        val index = RenderIndex().apply { update(board) }
        assertEquals(board.size, index.query(Rect(-1e7f, -1e7f, 1e7f, 1e7f)).size)
    }

    @Test
    fun aRotatedElementIsFoundWhereItsCornersSwingTo() {
        val tilted = box("t", 0f, 0f, 0, rotation = 45f)
        val index = RenderIndex().apply { update(listOf(tilted)) }
        // A corner of the 100-unit box turned 45 degrees reaches ~70.7 from its centre (50, 50).
        assertEquals(listOf("t"), index.query(Rect(115f, 45f, 120f, 55f)).map { it.id })
    }

    @Test
    fun onlyChangedElementsAreReportedWithWhereTheyWereAndAreNow() {
        val index = RenderIndex().apply { update(board) }
        index.takeDirty()
        val moved = board[5].translate(Offset(30_000f, 0f))
        index.update(board.toMutableList().also { it[5] = moved })
        val changes = index.takeDirty()
        assertEquals(setOf("b5"), changes.map { it.id }.toSet())
        assertTrue(changes.any { it.bounds.contains(Offset(5050f, 50f)) }, "where it was")
        assertTrue(changes.any { it.bounds.contains(Offset(35_050f, 50f)) }, "where it is")
        assertTrue(index.takeDirty().isEmpty())
    }

    @Test
    fun aRemovedElementIsReportedWhereItWas() {
        val index = RenderIndex().apply { update(board.take(3)) }
        index.takeDirty()
        index.update(board.take(2))
        assertEquals(listOf("b2"), index.takeDirty().map { it.id })
    }

    @Test
    fun theSameStrokeKeepsItsPathAndAMovedOneGetsANewOne() {
        val cache = PathCache()
        val s = stroke("s", 0f, 0f, 0)
        val first = cache.strokeFor(s)
        assertSame(first, cache.strokeFor(s))
        assertSame(first, cache.strokeFor(s.copy(alpha = 0.5f)), "a recolour keeps the geometry")
        val moved = s.translate(Offset(10f, 0f)) as Element.Path
        assertTrue(first !== cache.strokeFor(moved))
        assertEquals(3f, first.uniformWidth)
    }

    @Test
    fun aMissFarFromEveryStrokeStillMissesAndAHitStillHits() {
        val strokes = (0 until 200).map { stroke("s$it", it * 300f, 0f, it) }
        assertEquals(null, topmostHit(strokes, Offset(-500f, -500f)))
        assertEquals("s7", topmostHit(strokes, Offset(7 * 300f + 40f, 0f))?.id)
    }

    @Test
    fun equalZIndexStillPicksTheLaterElement() {
        val a = box("a", 0f, 0f, 1).copy(fillColor = Color.Red)
        val b = box("b", 0f, 0f, 1).copy(fillColor = Color.Red)
        assertEquals("b", topmostHit(listOf(a, b), Offset(50f, 50f))?.id)
        assertEquals("a", topmostHit(listOf(b, a), Offset(50f, 50f))?.id)
    }
}
