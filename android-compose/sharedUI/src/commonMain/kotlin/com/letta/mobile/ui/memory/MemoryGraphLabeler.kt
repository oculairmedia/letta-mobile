package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphView

/** What one frame's labels depend on besides the graph: the focus, the zoom and the tuning. */
internal class MemoryLabelContext(
    val selectedId: String?,
    val hoveredId: String?,
    val zoom: Float,
    val tuning: MemoryGraphTuning,
)

/**
 * Which nodes the memory graph labels, built once per view (the priority order and each node's
 * degree) and asked once per frame. The backend and agents are always candidates. Any other node
 * is a candidate when it is selected or hovered, a neighbour of one, has at least
 * [MemoryGraphTuning.labelMinDegree] links, the zoom reaches [MemoryGraphTuning.labelZoomThreshold],
 * or the whole graph is small ([MemoryGraphTuning.labelAllUnderNodes]). With culling on, labels are
 * placed in priority order (selected and hovered, the backend, agents, then the rest; each by
 * degree, then title, then id) and any whose chip overlaps one already placed is skipped, agents
 * included (a fleet of a hundred agents cannot label them all); only the focus and the backend are
 * never skipped.
 *
 * So labels hold still while nodes move, the set is re-decided only every
 * [MemoryGraphTuning.labelCullInterval] frames while the graph is [moving], once more as it comes
 * to rest, and then not again until the focus or zoom changes (which re-decides it at once, in the
 * same frame). A culling decision also has hysteresis: a shown label stays until its chip, shrunk by
 * [MemoryGraphTuning.labelHysteresisMargin], has overlapped for
 * [MemoryGraphTuning.labelHysteresisCount] decisions running; a hidden one returns only once its
 * chip, grown by that margin, has been clear for as many. A node that just became a candidate (say
 * a hovered agent's block) is placed on its first decision.
 */
internal class MemoryGraphLabeler(view: MemoryGraphView, private val neighbours: Map<String, List<String>>) {
    private val nodeCount = view.nodes.size
    private val degree: Map<String, Int> = view.nodes.associate { it.id to neighbours[it.id].orEmpty().size }

    /** Every node by static priority: the backend, agents, then the rest; then degree (most first), title, id. */
    val order: List<MemoryGraphNode> = view.nodes.sortedWith(
        compareBy<MemoryGraphNode>({ rank(it) }, { -(degree[it.id] ?: 0) }, { it.title }, { it.id }),
    )

    /** Whether the graph is moving this frame (set by the canvas before it asks). */
    var moving: Boolean = false

    /** True while a frame re-decides the labels: placement may recompute then, and holds still otherwise. */
    var deciding: Boolean = false
        private set

    private val directions = HashMap<String, Offset>(view.nodes.size)

    /**
     * Which side of its node [id]'s label hangs on: recomputed by [compute] only while [deciding]
     * (or the first time), so between decisions a label rides along with its node instead of
     * swinging round it as the neighbours move.
     */
    fun direction(id: String, compute: () -> Offset): Offset {
        val cached = directions[id]
        if (cached != null && !deciding) return cached
        return compute().also { directions[id] = it }
    }

    private val placed = FloatArray(view.nodes.size * RECT_FLOATS)
    private var placedCount = 0
    private val shown = HashSet<String>(view.nodes.size)
    private val entries = order.mapIndexed { index, node -> MemoryLabelEntry(index, node) }
    private val hysteresis = MemoryLabelHysteresis(order, ::overlapsPlaced)
    private val schedule = MemoryLabelSchedule()

    /** The nodes labelled this frame; [chipOf] is each candidate's label chip on screen (null: none). */
    fun shown(context: MemoryLabelContext, chipOf: (MemoryGraphNode) -> Rect?): Set<String> {
        if (!schedule.due(context, moving)) return shown
        deciding = true
        decide(context, chipOf)
        deciding = false
        return shown
    }

    /** Whether [node] may be labelled this frame, before culling. */
    fun isCandidate(node: MemoryGraphNode, context: MemoryLabelContext): Boolean {
        return isRoot(node) || isFocus(node, context) || hasContextLabel(node.id, context)
    }

    private fun hasContextLabel(id: String, context: MemoryLabelContext): Boolean {
        val tuning = context.tuning
        if (tuning.labelNeighboursOnFocus && isFocusNeighbour(id, context)) return true
        if ((degree[id] ?: 0) >= tuning.labelMinDegree) return true
        return context.zoom >= tuning.labelZoomThreshold || nodeCount <= tuning.labelAllUnderNodes
    }

    private fun decide(context: MemoryLabelContext, chipOf: (MemoryGraphNode) -> Rect?) {
        shown.clear()
        placedCount = 0
        entries.forEach { entry ->
            if (isFocus(entry.node, context)) settle(entry, chipOf(entry.node), keep = true)
        }
        entries.forEach { entry ->
            if (!isFocus(entry.node, context)) decideOrdinary(entry, context, chipOf)
        }
    }

    private fun decideOrdinary(entry: MemoryLabelEntry, context: MemoryLabelContext, chipOf: (MemoryGraphNode) -> Rect?) {
        if (!isCandidate(entry.node, context)) return hysteresis.forget(entry)
        val chip = chipOf(entry.node) ?: return hysteresis.forget(entry)
        val always = entry.node.kind == MemoryGraphNodeKind.Backend || !context.tuning.labelCulling
        settle(entry, chip, always || hysteresis.keeps(entry, chip, context.tuning))
    }

    private fun settle(entry: MemoryLabelEntry, chip: Rect?, keep: Boolean) {
        val showing = hysteresis.record(entry, chip, keep)
        if (!showing || chip == null) return
        shown += entry.node.id
        if (placedCount * RECT_FLOATS < placed.size) {
            val at = placedCount * RECT_FLOATS
            placed[at] = chip.left
            placed[at + 1] = chip.top
            placed[at + 2] = chip.right
            placed[at + 3] = chip.bottom
            placedCount++
        }
    }

    private fun isFocus(node: MemoryGraphNode, context: MemoryLabelContext): Boolean =
        node.id == context.selectedId || node.id == context.hoveredId

    private fun isFocusNeighbour(id: String, context: MemoryLabelContext): Boolean =
        listOfNotNull(context.selectedId, context.hoveredId).any { focus -> neighbours[focus]?.contains(id) == true }

    private fun overlapsPlaced(chip: Rect): Boolean {
        for (index in 0 until placedCount) {
            val at = index * RECT_FLOATS
            val apart = chip.right <= placed[at] || chip.left >= placed[at + 2] ||
                chip.bottom <= placed[at + 1] || chip.top >= placed[at + 3]
            if (!apart) return true
        }
        return false
    }

    private companion object {
        const val RECT_FLOATS = 4

        fun isRoot(node: MemoryGraphNode): Boolean = node.kind == MemoryGraphNodeKind.Agent || node.kind == MemoryGraphNodeKind.Backend

        /** The backend first, then agents, then everything else. */
        fun rank(node: MemoryGraphNode): Int = when (node.kind) {
            MemoryGraphNodeKind.Backend -> 0
            MemoryGraphNodeKind.Agent -> 1
            else -> 2
        }
    }
}
