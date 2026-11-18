package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.graph.MemoryGraphViews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which memory graph labels show: roots always, the rest by focus, degree and zoom, overlaps culled. */
class MemoryGraphLabelerTest {
    private val view = MemoryGraphViews.build(graph(), emptySet())
    private val labeler = MemoryGraphLabeler(view, view.adjacency())

    /** Declutter as a fleet does, with no small-graph exception, at degree 3 and zoom 1.6. */
    private val tuning = MemoryGraphTestTunings.classic.copy(labelAllUnderNodes = 0f)

    @Test
    fun thePriorityOrderIsBackendThenAgentsThenTheRestEachByDegreeThenTitle() {
        val order = labeler.order.map { it.id }
        assertEquals(listOf("backend", "a1", "a2", "persona", "human", "leaf-a", "leaf-b"), order)
        assertEquals(order, MemoryGraphLabeler(view, view.adjacency()).order.map { it.id })
    }

    @Test
    fun aLeafBelowTheDegreeAndZoomThresholdsIsUnlabelled() {
        val shown = labeler.shown(context(), ::apart)
        assertEquals(setOf("a1", "a2", "backend", "persona", "human"), shown.toSet())
        assertFalse("leaf-a" in shown)
        assertTrue("leaf-a" in labeler.shown(context(zoom = 2f), ::apart))
    }

    @Test
    fun hoveringOrSelectingANodeLabelsItsNeighbours() {
        assertTrue("leaf-a" in labeler.shown(context(hovered = "a1"), ::apart).toSet())
        assertTrue("leaf-b" in labeler.shown(context(selected = "a2"), ::apart).toSet())
        assertFalse("leaf-b" in labeler.shown(context(hovered = "a1"), ::apart).toSet())
        val quiet = MemoryLabelContext(null, "a1", 1f, tuning.copy(labelNeighboursOnFocus = false))
        assertFalse("leaf-a" in labeler.shown(quiet, ::apart).toSet())
    }

    @Test
    fun cullingDropsTheLowerPriorityOfTwoOverlappingLabels() {
        // "persona" (degree 4) and "human" (degree 3) share one spot; every other label has its own.
        val clash = { node: MemoryGraphNode -> if (node.id == "persona" || node.id == "human") CLASH else apart(node) }
        val shown = labeler.shown(context(), clash).toSet()
        assertTrue("persona" in shown)
        assertFalse("human" in shown)
        val unculled = labeler.shown(MemoryLabelContext(null, null, 1f, tuning.copy(labelCulling = false)), clash).toSet()
        assertTrue("human" in unculled)
    }

    @Test
    fun onlyTheFocusAndTheBackendAreNeverCulled() {
        val everything = { _: MemoryGraphNode -> CLASH }
        val shown = labeler.shown(context(hovered = "leaf-a"), everything).toSet()
        assertEquals(setOf("leaf-a", "backend"), shown)
    }

    @Test
    fun agentsAreCulledLikeAnyOtherLabel() {
        // The two agents share one spot: the first by priority keeps its label.
        val agentsClash = { node: MemoryGraphNode -> if (node.id == "a1" || node.id == "a2") CLASH else apart(node) }
        val shown = labeler.shown(context(), agentsClash).toSet()
        assertTrue("a1" in shown)
        assertFalse("a2" in shown)
    }

    @Test
    fun aLabelJitteringAcrossAnOverlapBoundaryDoesNotFlicker() {
        val steady = MemoryLabelContext(null, null, 1f, tuning.copy(labelCullInterval = 1f))
        assertTrue(jitter(MemoryGraphLabeler(view, view.adjacency()), steady).all { it }, "hysteresis holds the label")
        val twitchy = MemoryLabelContext(null, null, 1f, steady.tuning.copy(labelHysteresisCount = 1f, labelHysteresisMargin = 0f))
        val flicker = jitter(MemoryGraphLabeler(view, view.adjacency()), twitchy).toSet()
        assertEquals(setOf(true, false), flicker, "without it the label flickers")
    }

    @Test
    fun theLabelsHoldOnceTheGraphStopsUntilFocusChanges() {
        labeler.moving = true
        labeler.shown(context(), ::apart)
        labeler.moving = false
        val rested = labeler.shown(context(), ::apart).toSet()
        // Even a layout that would now cull "human" leaves the rested labels as they are.
        val clash = { node: MemoryGraphNode -> if (node.id == "persona" || node.id == "human") CLASH else apart(node) }
        repeat(FRAMES) { assertEquals(rested, labeler.shown(context(), clash).toSet()) }
    }

    @Test
    fun hoveringUpdatesTheLabelsInTheSameFrame() {
        labeler.moving = false
        repeat(FRAMES) { labeler.shown(context(), ::apart) }
        assertFalse("leaf-a" in labeler.shown(context(), ::apart))
        assertTrue("leaf-a" in labeler.shown(context(hovered = "a1"), ::apart), "the hovered agent's block is labelled at once")
    }

    /**
     * Frames of "human" jittering a pixel either side of touching "persona" while the graph moves:
     * whether its label showed each frame.
     */
    private fun jitter(labeler: MemoryGraphLabeler, context: MemoryLabelContext): List<Boolean> {
        labeler.moving = true
        return (0 until FRAMES).map { frame ->
            val left = CLASH.right + if (frame % 2 == 0) 1f else -1f
            val chips = { node: MemoryGraphNode ->
                when (node.id) {
                    "persona" -> CLASH
                    "human" -> Rect(Offset(left, CLASH.top), Size(CHIP_WIDTH, CHIP_HEIGHT))
                    else -> apart(node)
                }
            }
            "human" in labeler.shown(context, chips)
        }
    }

    @Test
    fun labelsFadeInOverTheDurationAndInstantlyUnderReducedMotion() {
        val fade = MemoryLabelFade()
        fade.advance(setOf("a1"), 0L, DURATION)
        assertEquals(1f, fade.alphaOf("a1"), "the first frame shows its labels at once")
        assertTrue(fade.advance(setOf("a1", "leaf-a"), FRAME, DURATION))
        assertTrue(fade.alphaOf("leaf-a") in 0.01f..0.5f, "a new label starts fading in: ${fade.alphaOf("leaf-a")}")
        fade.advance(setOf("a1", "leaf-a"), FRAME + DURATION, DURATION)
        assertEquals(1f, fade.alphaOf("leaf-a"))
        fade.advance(setOf("a1"), FRAME + DURATION, 0L)
        assertEquals(0f, fade.alphaOf("leaf-a"), "reduced motion hides at once")
    }

    private fun context(hovered: String? = null, selected: String? = null, zoom: Float = 1f) =
        MemoryLabelContext(selected, hovered, zoom, tuning)

    /** Every node's chip in its own row, so nothing overlaps. */
    private fun apart(node: MemoryGraphNode): Rect {
        val row = view.nodes.indexOf(node)
        return Rect(Offset(0f, row * ROW), Size(CHIP_WIDTH, CHIP_HEIGHT))
    }

    /** Two agents sharing persona (both, plus the backend) and human; each with a leaf of its own. */
    private fun graph(): MemoryParityGraph {
        val nodes = listOf(
            node("backend", "Backend", MemoryGraphNodeKind.Backend),
            node("a1", "Ada", MemoryGraphNodeKind.Agent),
            node("a2", "Bo", MemoryGraphNodeKind.Agent),
            node("persona", "persona", MemoryGraphNodeKind.Memory),
            node("human", "human", MemoryGraphNodeKind.Memory),
            node("leaf-a", "notes a", MemoryGraphNodeKind.Memory),
            node("leaf-b", "notes b", MemoryGraphNodeKind.Memory),
        )
        val edges = listOf(
            edge("backend", "a1"), edge("backend", "a2"),
            edge("a1", "persona"), edge("a2", "persona"), edge("backend", "persona"), edge("leaf-a", "persona"),
            edge("a1", "human"), edge("a2", "human"), edge("backend", "human"),
            edge("a1", "leaf-a"), edge("a2", "leaf-b"),
        )
        return MemoryParityGraph(nodes, edges)
    }

    private fun node(id: String, title: String, kind: MemoryGraphNodeKind) = MemoryGraphNode(id, title, "", kind)

    private fun edge(from: String, to: String) = MemoryGraphEdge("$from->$to", from, to, "links", MemoryGraphEdgeKind.Remembers)

    private companion object {
        const val ROW = 40f
        const val CHIP_WIDTH = 80f
        const val CHIP_HEIGHT = 20f
        val CLASH = Rect(Offset(500f, 500f), Size(CHIP_WIDTH, CHIP_HEIGHT))
        const val DURATION = 160_000_000L
        const val FRAME = 16_000_000L
        const val FRAMES = 20
    }
}
