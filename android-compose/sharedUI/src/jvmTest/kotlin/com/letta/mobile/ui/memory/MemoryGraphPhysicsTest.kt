package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.Offset
import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutEngine
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.data.memory.graph.MemoryGraphViews
import com.letta.mobile.ui.components.lettaCount
import com.letta.mobile.ui.components.lettaPercent
import com.letta.mobile.ui.components.lettaUsage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The memory graph's live layout, label rules and number formatting. */
class MemoryGraphPhysicsTest {
    private val view: MemoryGraphView = MemoryGraphViews.build(star(6), emptySet())
    private val target: MemoryGraphLayout = MemoryGraphLayoutEngine.layout(view)

    private fun physics(previous: MemoryGraphLayout? = null, motion: Boolean = true) = MemoryGraphPhysics.create(
        view = view,
        target = target,
        radiusOf = { MemoryNodeLooks.SMALL_RADIUS },
        start = MemoryGraphPhysicsStart(previous = previous, bloom = true, motion = motion),
    )

    @Test
    fun aFirstOpenBloomsFromTheCentreAndSettlesNearTheControllersLayout() {
        val sim = physics()
        assertTrue(sim.isHot, "a first open blooms")
        val spread = { layout: MemoryGraphLayout -> layout.bounds.width }
        assertTrue(spread(sim.snapshot()) < spread(target) * 0.5f, "the bloom starts compressed")
        var ticks = 0
        while (sim.tick()) ticks++
        assertTrue(ticks in 10..400, "settled in $ticks ticks")
        assertFalse(sim.isHot)
        val settled = sim.snapshot()
        assertTrue(abs(spread(settled) - spread(target)) < spread(target) * 0.15f, "settled near the target spread")
        assertFalse(sim.tick(), "a settled graph does no more work")
    }

    @Test
    fun aPinnedNodeStaysPutWhileItsNeighboursFollow() {
        val sim = physics(previous = target)
        assertFalse(sim.isHot, "an unchanged graph does not move")
        val hub = "agent"
        val leaf = "leaf-0"
        val before = sim.snapshot()[leaf]!!
        val pin = MemoryGraphPoint(target[hub]!!.x + 200f, target[hub]!!.y)
        repeat(30) {
            sim.pin(hub, pin)
            sim.tick()
        }
        assertEquals(pin, sim.snapshot()[hub])
        val after = sim.snapshot()[leaf]!!
        assertTrue(hypot(after.x - before.x, after.y - before.y) > 5f, "a neighbour follows the dragged hub")
        sim.release()
        while (sim.tick()) Unit
        assertFalse(sim.isHot)
    }

    @Test
    fun reducedMotionNeverHeatsAndADraggedNodeMovesAlone() {
        val sim = physics(motion = false)
        assertFalse(sim.isHot, "no bloom under reduced motion")
        assertEquals(target.positions, sim.snapshot().positions, "the graph opens settled")
        val pin = MemoryGraphPoint(10f, 10f)
        sim.pin("leaf-1", pin)
        assertFalse(sim.isHot)
        assertFalse(sim.tick())
        assertEquals(pin, sim.snapshot()["leaf-1"])
        assertEquals(target["leaf-2"], sim.snapshot()["leaf-2"], "nothing else drifts")
    }

    @Test
    fun labelsKeepTheMeaningfulPart() {
        assertEquals("Emmanuel authorized…", MemoryLabelText.shorten("Emmanuel authorized check-ins every weekday"))
        val id = MemoryLabelText.shorten("iroh://330415cc15c111a9f0e2b7d4c8a1e5f6")
        assertTrue(id.startsWith("iroh://3304") && id.endsWith("a1e5f6") && "…" in id, id)
        assertEquals("persona", MemoryLabelText.shorten("persona"))
    }

    @Test
    fun aLeafsLabelPointsAwayFromItsHub() {
        val direction = MemoryLabelPlacement.direction(Offset(100f, 0f), listOf(Offset(0f, 0f)))
        assertTrue(direction.x > 0.99f, "label points away from the hub: $direction")
        val below = MemoryLabelPlacement.direction(Offset.Zero, emptyList())
        assertEquals(Offset(0f, 1f), below)
    }

    @Test
    fun numbersAreGrouped() {
        assertEquals("1,000,000", lettaCount(1_000_000))
        assertEquals("801 / 1,000,000 tokens", lettaUsage(801, 1_000_000, "tokens"))
        assertEquals("<0.1%", lettaPercent(801, 1_000_000))
        assertEquals("4%", lettaPercent(4, 100))
        assertEquals("37%", lettaPercent(1_840, 5_000))
    }

    private fun star(leaves: Int): MemoryParityGraph {
        val hub = MemoryGraphNode("agent", "Agent", "", MemoryGraphNodeKind.Agent)
        val nodes = (0 until leaves).map { MemoryGraphNode("leaf-$it", "Leaf $it", "", MemoryGraphNodeKind.Memory) }
        val edges = nodes.map { MemoryGraphEdge("agent->${it.id}", "agent", it.id, "remembers", MemoryGraphEdgeKind.Remembers) }
        return MemoryParityGraph(listOf(hub) + nodes, edges)
    }
}
