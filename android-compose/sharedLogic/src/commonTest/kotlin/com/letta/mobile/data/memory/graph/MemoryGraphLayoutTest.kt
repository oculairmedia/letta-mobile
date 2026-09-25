package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryGraphLayoutTest {
    @Test
    fun forceLayoutIsDeterministic() {
        val view = MemoryGraphFixtures.view()

        val first = MemoryGraphLayoutEngine.layout(view)
        val second = MemoryGraphLayoutEngine.layout(view)

        assertEquals(first, second)
    }

    @Test
    fun layoutPositionsEveryVisibleNode() {
        val view = MemoryGraphFixtures.view()

        val layout = MemoryGraphLayoutEngine.layout(view)

        assertEquals(view.nodes.map { it.id }.toSet(), layout.positions.keys)
    }

    @Test
    fun forceLayoutKeepsNodesApart() {
        val layout = MemoryGraphLayoutEngine.layout(MemoryGraphFixtures.view())
        val points = layout.positions.values.toList()

        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                assertTrue(distance(points[i], points[j]) > MIN_SEPARATION, "nodes $i and $j overlap")
            }
        }
    }

    @Test
    fun forceLayoutSettlesTheHubNearTheCentre() {
        val layout = MemoryGraphLayoutEngine.layout(MemoryGraphFixtures.view())
        val root = layout.positions.getValue(MemoryGraphFixtures.ROOT_ID)
        val others = layout.positions.filterKeys { it != MemoryGraphFixtures.ROOT_ID }.values
        val meanX = others.map { it.x }.average().toFloat()
        val meanY = others.map { it.y }.average().toFloat()

        val hubOffset = distance(root, MemoryGraphPoint(meanX, meanY))
        val meanSpoke = others.map { distance(root, it) }.average().toFloat()

        assertTrue(hubOffset < meanSpoke / 2f, "hub $hubOffset should sit inside its spokes $meanSpoke")
    }

    @Test
    fun radialLayoutPutsTheHubAtTheOriginAndLeavesOnOneRing() {
        val layout = MemoryGraphLayoutEngine.layout(MemoryGraphFixtures.view(), MemoryGraphLayoutMode.Radial)

        assertEquals(MemoryGraphPoint(0f, 0f), layout.positions.getValue(MemoryGraphFixtures.ROOT_ID))
        val radii = layout.positions.filterKeys { it != MemoryGraphFixtures.ROOT_ID }.values.map { distance(it, MemoryGraphPoint(0f, 0f)) }
        assertTrue(radii.all { kotlin.math.abs(it - radii.first()) < RING_TOLERANCE })
    }

    @Test
    fun boundsCoverEveryPosition() {
        val layout = MemoryGraphLayoutEngine.layout(MemoryGraphFixtures.view())

        layout.positions.values.forEach { point ->
            assertTrue(point.x in layout.bounds.minX..layout.bounds.maxX)
            assertTrue(point.y in layout.bounds.minY..layout.bounds.maxY)
        }
    }

    @Test
    fun emptyViewHasEmptyLayout() {
        val layout = MemoryGraphLayoutEngine.layout(MemoryGraphView())

        assertTrue(layout.isEmpty)
        assertEquals(MemoryGraphBounds.Empty, layout.bounds)
    }

    @Test
    fun largeGraphStaysWithinTheWorkBudget() {
        val blocks = (0 until LARGE_GRAPH_BLOCKS).map { MemoryGraphFixtures.BlockSpec("b$it", "block_$it", "v$it") }
        val view = MemoryGraphFixtures.view(MemoryGraphFixtures.memory(blocks = blocks, skills = emptyList()))

        val layout = MemoryGraphLayoutEngine.layout(view)

        assertEquals(LARGE_GRAPH_BLOCKS + 1, layout.positions.size)
        assertTrue(layout.positions.values.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(view.nodes.count { it.kind == MemoryGraphNodeKind.Memory } == LARGE_GRAPH_BLOCKS)
    }

    private fun distance(a: MemoryGraphPoint, b: MemoryGraphPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val MIN_SEPARATION = 20f
        const val RING_TOLERANCE = 0.01f
        const val LARGE_GRAPH_BLOCKS = 300
    }
}
