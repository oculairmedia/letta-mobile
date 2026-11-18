package com.letta.mobile.data.memory.graph.forces

import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.graph.MemoryGraphForceField
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.data.memory.graph.MemoryGraphViews
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each node's and link's constants vary by its own seed: stably, within bounds, independently, off at naturalness 0. */
class ForceElementsTest {
    private val view = MemoryGraphViews.build(star(LEAVES), emptySet())
    /** The shipped spreads (and naturalness) with every node kind alike and no degree effects, so each value's base is plain. */
    private val tuning = MemoryGraphTuning(degreeScaling = 0f, linkLengthDegreeBias = 0f, nodeCharge = emptyMap())

    @Test
    fun theSameGraphGetsTheSameValues() {
        val first = applied(view, tuning)
        val second = applied(view, tuning)
        assertContentEquals(first.charge, second.charge)
        assertContentEquals(first.gravity, second.gravity)
        assertContentEquals(first.cluster, second.cluster)
        assertContentEquals(first.stiffness, second.stiffness)
        assertContentEquals(first.restLength, second.restLength)
    }

    @Test
    fun everyValueStaysWithinItsSpread() {
        val elements = applied(view, tuning)
        val spreads = tuning.spreads
        within(elements.charge, 1f, spreads.charge)
        within(elements.gravity, 1f, spreads.gravity)
        within(elements.cluster, 1f, spreads.cluster)
        within(elements.stiffness, tuning.stiffnessOf(MemoryGraphEdgeKind.Remembers), spreads.stiffness)
        val lengthSpread = tuning.lengthSpreadOfKind(MemoryGraphEdgeKind.Remembers.ordinal)
        within(elements.restLength, tuning.lengthOf(MemoryGraphEdgeKind.Remembers), lengthSpread)
        assertTrue(elements.charge.toSet().size > 1 && elements.stiffness.toSet().size > 1, "values vary")
    }

    @Test
    fun eachConstantVariesIndependently() {
        val seeds = (0 until SAMPLES).map { Variation.hash("node-$it") }
        val charge = seeds.map { Variation.factor(it, Variation.CHARGE, 1f) }
        val radius = seeds.map { Variation.factor(it, Variation.RADIUS, 1f) }
        val gravity = seeds.map { Variation.factor(it, Variation.GRAVITY, 1f) }
        assertTrue(abs(correlation(charge, radius)) < INDEPENDENT, "charge and radius move together")
        assertTrue(abs(correlation(charge, gravity)) < INDEPENDENT, "charge and gravity move together")
    }

    @Test
    fun addingANodeLeavesEveryOtherElementAsItWas() {
        val before = applied(view, tuning)
        val grown = MemoryGraphViews.build(
            star(LEAVES).let { graph ->
                val other = MemoryGraphNode("other-agent", "other-agent", "", MemoryGraphNodeKind.Agent)
                val newcomer = MemoryGraphNode("newcomer", "newcomer", "", MemoryGraphNodeKind.Memory)
                MemoryParityGraph(graph.nodes + other + newcomer, graph.edges + edge(other.id, newcomer.id))
            },
            emptySet(),
        )
        val after = applied(grown, tuning)
        view.nodes.indices.forEach { i ->
            assertEquals(before.charge[i], after.charge[i])
            assertEquals(before.gravity[i], after.gravity[i])
            assertEquals(before.cluster[i], after.cluster[i])
        }
        view.edges.indices.forEach { s ->
            assertEquals(before.stiffness[s], after.stiffness[s])
            assertEquals(before.restLength[s], after.restLength[s])
        }
    }

    @Test
    fun naturalnessZeroIsTheUnvariedModelExactly() {
        val off = applied(view, tuning.copy(naturalness = 0f))
        val unseeded = ForceElements(view.nodes.size, MemoryGraphForceField.springsOf(view), 0f, 0f).also {
            val elements = MemoryGraphForceField.elementsOf(view)
            it.apply(tuning, GraphElements(elements.nodeKinds, elements.springKinds))
        }
        assertContentEquals(unseeded.charge, off.charge)
        assertContentEquals(unseeded.gravity, off.gravity)
        assertContentEquals(unseeded.cluster, off.cluster)
        assertContentEquals(unseeded.stiffness, off.stiffness)
        assertContentEquals(unseeded.restLength, off.restLength)
        assertTrue(off.gravity.all { it == 1f } && off.cluster.all { it == 1f })
    }

    private fun applied(graph: MemoryGraphView, tuning: MemoryGraphTuning): ForceElements =
        ForceElements(graph.nodes.size, MemoryGraphForceField.springsOf(graph), 0f, 0f).also {
            it.apply(tuning, MemoryGraphForceField.elementsOf(graph))
        }

    private fun within(values: FloatArray, base: Float, spread: Float) {
        values.forEach { value ->
            val inside = value >= base * (1f - spread) - EPSILON && value <= base * (1f + spread) + EPSILON
            assertTrue(inside, "$value outside $base ± $spread")
        }
    }

    private fun correlation(a: List<Float>, b: List<Float>): Double {
        val ma = a.average()
        val mb = b.average()
        val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
        val va = a.sumOf { (it - ma) * (it - ma) }
        val vb = b.sumOf { (it - mb) * (it - mb) }
        return cov / sqrt(va * vb)
    }

    private fun star(leaves: Int): MemoryParityGraph {
        val agent = MemoryGraphNode("agent", "agent", "", MemoryGraphNodeKind.Agent)
        val blocks = (1..leaves).map { MemoryGraphNode("block-$it", "block-$it", "", MemoryGraphNodeKind.Memory) }
        return MemoryParityGraph(listOf(agent) + blocks, blocks.map { edge(agent.id, it.id) })
    }

    private fun edge(from: String, to: String) = MemoryGraphEdge("$from->$to", from, to, "remembers", MemoryGraphEdgeKind.Remembers)

    private companion object {
        const val LEAVES = 24
        const val SAMPLES = 500
        const val INDEPENDENT = 0.2
        const val EPSILON = 1e-4f
    }
}
