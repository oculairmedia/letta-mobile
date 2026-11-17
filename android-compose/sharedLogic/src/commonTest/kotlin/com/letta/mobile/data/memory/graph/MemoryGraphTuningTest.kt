package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGraphTuningTest {
    private val config = MemoryGraphLayoutConfig()

    @Test
    fun theDefaultsAreTheShippedConstants() {
        val tuning = MemoryGraphTuning()
        assertEquals(config.repulsion, tuning.repulsion)
        assertEquals(config.gravity, tuning.gravity)
        assertEquals(config.damping, tuning.sparseDamping)
        assertEquals(config.maxStep, tuning.sparseMaxSpeed)
        assertEquals(0f, tuning.repulsionCutoff)
        MemoryGraphNodeKind.entries.forEach { kind ->
            assertEquals(1f, tuning.chargeOf(kind))
            assertEquals(1f, tuning.radiusScaleOf(kind))
        }
        MemoryGraphEdgeKind.entries.forEach { kind ->
            assertEquals(config.idealEdgeLength, tuning.lengthOf(kind))
            assertEquals(config.attraction, tuning.stiffnessOf(kind))
        }
        assertEquals(listOf(10f, 0.6f, 0.6f, 12f, 40f, 240f), listOf(
            tuning.collisionPadding, tuning.collisionStrength, tuning.crowdedDamping,
            tuning.crowdedMaxSpeed, tuning.sparseCrowding, tuning.crowdedCrowding,
        ))
        assertEquals(listOf(1f, 150f, 0.001f, 0.04f, 360f), listOf(
            tuning.alphaStart, tuning.coolingFrames, tuning.alphaMin, tuning.settleSpeed, tuning.maxFramesPerHeat,
        ))
    }

    @Test
    fun aFieldTunedWithTheDefaultsStepsExactlyLikeAnUntunedOne() {
        val view = MemoryGraphViews.build(star(), emptySet())
        val seed = MemoryGraphLayoutEngine.layout(view, MemoryGraphLayoutMode.Radial)
        val plain = field(view, seed)
        val tuned = field(view, seed).also {
            it.applyTuning(MemoryGraphTuning(), nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
        }
        val motion = MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size)
        repeat(STEPS) {
            plain.step()
            tuned.step(MemoryGraphForceField.FREE, motion, 1f)
        }
        view.nodes.indices.forEach { assertEquals(plain.point(it), tuned.point(it)) }
    }

    @Test
    fun aKindsChargeScalesHowHardItRepels() {
        val baseline = pairPush(MemoryGraphTuning(repulsion = WEAK))
        val charged = pairPush(MemoryGraphTuning(repulsion = WEAK, nodeCharge = mapOf(MemoryGraphNodeKind.Agent to 2f)))
        assertTrue(abs(charged / baseline - 2f) < 0.05f, "charge 2 doubled the push: $baseline -> $charged")
    }

    @Test
    fun nodesBeyondTheCutOffDoNotRepel() {
        val unlimited = pairPush(MemoryGraphTuning(repulsion = WEAK, gravity = 0f))
        val cut = pairPush(MemoryGraphTuning(repulsion = WEAK, gravity = 0f, repulsionCutoff = PAIR_DISTANCE / 2))
        assertTrue(unlimited > 0f)
        assertEquals(0f, cut)
    }

    @Test
    fun aSpringKindsLengthIsWhereItRests() {
        val view = MemoryGraphViews.build(pair(), emptySet())
        val tuning = MemoryGraphTuning(repulsion = 0f, gravity = 0f, springLength = mapOf(MemoryGraphEdgeKind.Runs to PAIR_DISTANCE))
        val field = MemoryGraphForceField(2, MemoryGraphForceField.springsOf(view))
        field.applyTuning(tuning, nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
        field.place(0, MemoryGraphPoint(0f, 0f))
        field.place(1, MemoryGraphPoint(PAIR_DISTANCE, 0f))
        field.step(MemoryGraphForceField.FREE, MemoryGraphMotion.forGraph(2, 1, tuning), 1f)
        assertEquals(MemoryGraphPoint(PAIR_DISTANCE, 0f), field.point(1), "a spring at its rest length pulls nothing")
    }

    @Test
    fun aStoredTuningRoundTrips() {
        val tuned = MemoryGraphTuning(
            repulsion = 4200f,
            nodeCharge = MemoryGraphTuning().nodeCharge + (MemoryGraphNodeKind.Memory to 0.5f),
            springLength = MemoryGraphTuning().springLength + (MemoryGraphEdgeKind.Remembers to 80f),
            coolingFrames = 90f,
        )
        assertEquals(tuned, MemoryGraphTunings.decode(MemoryGraphTunings.encode(tuned)))
        assertEquals(MemoryGraphTuning(repulsion = 5000f), MemoryGraphTunings.decode("""{"repulsion": 5000.0}"""))
        assertNull(MemoryGraphTunings.decode("not json"))
    }

    @Test
    fun theKotlinSourceNamesEveryKnob() {
        val source = MemoryGraphTunings.kotlinSource(MemoryGraphTuning(repulsion = 4200f))
        assertTrue(source.startsWith("MemoryGraphTuning(\n"))
        assertTrue("    repulsion = 4200.0f," in source)
        assertTrue("MemoryGraphNodeKind.Agent to 1.0f" in source)
        assertTrue("MemoryGraphEdgeKind.Runs to 110.0f" in source)
        assertTrue("    maxFramesPerHeat = 360.0f,\n)" in source)
    }

    /** How far the agent of an agent–memory pair [PAIR_DISTANCE] apart moves in one step under [tuning]. */
    private fun pairPush(tuning: MemoryGraphTuning): Float {
        val view = MemoryGraphViews.build(MemoryParityGraph(nodes = pair().nodes), emptySet())
        val field = MemoryGraphForceField(2, IntArray(0))
        field.applyTuning(tuning, nodeKinds(view), IntArray(0))
        field.place(0, MemoryGraphPoint(0f, 0f))
        field.place(1, MemoryGraphPoint(PAIR_DISTANCE, 0f))
        field.step(1, MemoryGraphMotion.forGraph(2, 0, tuning), 1f)
        return abs(field.point(0).x)
    }

    private fun field(view: MemoryGraphView, seed: MemoryGraphLayout): MemoryGraphForceField =
        MemoryGraphForceField(view.nodes.size, MemoryGraphForceField.springsOf(view)).also { field ->
            view.nodes.forEachIndexed { index, node -> field.place(index, seed.getValue(node.id)) }
        }

    private fun MemoryGraphLayout.getValue(id: String): MemoryGraphPoint = checkNotNull(this[id])

    private fun nodeKinds(view: MemoryGraphView) = IntArray(view.nodes.size) { view.nodes[it].kind.ordinal }

    private fun pair() = MemoryParityGraph(
        nodes = listOf(node("agent", MemoryGraphNodeKind.Agent), node("block", MemoryGraphNodeKind.Memory)),
        edges = listOf(MemoryGraphEdge("agent->block", "agent", "block", "runs", MemoryGraphEdgeKind.Runs)),
    )

    private fun star(): MemoryParityGraph {
        val leaves = (1..LEAVES).map { node("block-$it", MemoryGraphNodeKind.Memory) }
        return MemoryParityGraph(
            nodes = listOf(node("agent", MemoryGraphNodeKind.Agent)) + leaves,
            edges = leaves.map { MemoryGraphEdge("agent->${it.id}", "agent", it.id, "remembers", MemoryGraphEdgeKind.Remembers) },
        )
    }

    private fun node(id: String, kind: MemoryGraphNodeKind) = MemoryGraphNode(id, id, id, kind)

    private companion object {
        const val STEPS = 40
        const val LEAVES = 6
        const val WEAK = 100f
        const val PAIR_DISTANCE = 10f
    }
}
