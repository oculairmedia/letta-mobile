package com.letta.mobile.data.memory.graph

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGraphViewportTest {
    private val limits = MemoryGraphZoomLimits.forDensity(DENSITY)

    @Test
    fun fitCentresTheBoundsOnScreen() {
        val bounds = MemoryGraphBounds(-100f, -50f, 300f, 150f)
        val size = MemoryGraphSize(800f, 600f)

        val viewport = MemoryGraphViewport.fit(bounds, size, padding = 40f, limits = limits)

        assertClose(MemoryGraphPoint(400f, 300f), viewport.toScreen(bounds.center))
        val topLeft = viewport.toScreen(MemoryGraphPoint(bounds.minX, bounds.minY))
        assertTrue(topLeft.x >= 39f && topLeft.y >= 0f)
    }

    @Test
    fun fitNeverZoomsPastTheFitCeiling() {
        val tiny = MemoryGraphBounds(0f, 0f, 2f, 2f)

        val viewport = MemoryGraphViewport.fit(tiny, MemoryGraphSize(1000f, 1000f), padding = 0f, limits = limits)

        assertEquals(limits.fitMaxScale, viewport.scale)
    }

    @Test
    fun zoomAboutKeepsTheFocusPointFixed() {
        val viewport = MemoryGraphViewport(scale = 2f, offsetX = 10f, offsetY = -20f)
        val focus = MemoryGraphPoint(250f, 125f)
        val anchor = viewport.toLayout(focus)

        val zoomed = viewport.zoomAbout(focus, factor = 1.5f, limits = limits)

        assertEquals(3f, zoomed.scale)
        assertClose(focus, zoomed.toScreen(anchor))
    }

    @Test
    fun zoomIsClampedToTheLimits() {
        val zoomed = MemoryGraphViewport(scale = limits.maxScale).zoomAbout(MemoryGraphPoint(0f, 0f), 10f, limits)

        assertEquals(limits.maxScale, zoomed.scale)
    }

    @Test
    fun toLayoutInvertsToScreen() {
        val viewport = MemoryGraphViewport(scale = 1.7f, offsetX = 33f, offsetY = 12f)
        val point = MemoryGraphPoint(-41f, 88f)

        assertClose(point, viewport.toLayout(viewport.toScreen(point)))
    }

    @Test
    fun hitTestFindsTheNearestNodeWithinTouchReach() {
        val view = MemoryGraphFixtures.view()
        val layout = MemoryGraphLayoutEngine.layout(view, MemoryGraphLayoutMode.Radial)
        val viewport = MemoryGraphViewport(scale = 1f, offsetX = 500f, offsetY = 500f)
        val target = MemoryGraphHitTarget(view, layout, viewport, minTouchRadiusPx = 24f)
        val personaId = MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.persona)
        val persona = viewport.toScreen(layout.positions.getValue(personaId))

        val hit = MemoryGraphHitTest.nodeAt(MemoryGraphPoint(persona.x + 20f, persona.y), target)

        assertEquals(personaId, hit)
    }

    @Test
    fun hitTestMissesEmptySpace() {
        val view = MemoryGraphFixtures.view()
        val layout = MemoryGraphLayoutEngine.layout(view, MemoryGraphLayoutMode.Radial)
        val target = MemoryGraphHitTarget(view, layout, MemoryGraphViewport(), minTouchRadiusPx = 24f)

        assertNull(MemoryGraphHitTest.nodeAt(MemoryGraphPoint(5000f, 5000f), target))
    }

    @Test
    fun nodeRadiusGrowsWithDegreeWithinDesktopClamp() {
        assertEquals(8f, MemoryGraphNodeMetrics.radius(0))
        assertEquals(10f, MemoryGraphNodeMetrics.radius(1))
        assertEquals(20f, MemoryGraphNodeMetrics.radius(50))
    }

    private fun assertClose(expected: MemoryGraphPoint, actual: MemoryGraphPoint) {
        assertTrue(abs(expected.x - actual.x) < EPSILON && abs(expected.y - actual.y) < EPSILON, "$expected != $actual")
    }

    private companion object {
        const val DENSITY = 2f
        const val EPSILON = 0.01f
    }
}
