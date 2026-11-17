package com.letta.mobile.ui.memory

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphView
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel

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
    private val wasShown = BooleanArray(view.nodes.size)
    private val wasCandidate = BooleanArray(view.nodes.size)
    private val overlapStreak = IntArray(view.nodes.size)
    private val clearStreak = IntArray(view.nodes.size)
    private var last: MemoryLabelContext? = null
    private var wasMoving = false
    private var framesSinceDecision = 0

    /** The nodes labelled this frame; [chipOf] is each candidate's label chip on screen (null: none). */
    fun shown(context: MemoryLabelContext, chipOf: (MemoryGraphNode) -> Rect?): Set<String> {
        val previous = last
        val changed = previous == null || previous.selectedId != context.selectedId ||
            previous.hoveredId != context.hoveredId || previous.zoom != context.zoom || previous.tuning != context.tuning
        val settling = wasMoving && !moving
        framesSinceDecision++
        val due = changed || settling || (moving && framesSinceDecision >= context.tuning.labelCullInterval)
        last = context
        wasMoving = moving
        if (!due) return shown
        framesSinceDecision = 0
        deciding = true
        decide(context, chipOf)
        deciding = false
        return shown
    }

    /** Whether [node] may be labelled this frame, before culling. */
    fun isCandidate(node: MemoryGraphNode, context: MemoryLabelContext): Boolean {
        val tuning = context.tuning
        return isRoot(node) || node.id == context.selectedId || node.id == context.hoveredId ||
            (tuning.labelNeighboursOnFocus && isFocusNeighbour(node.id, context)) ||
            (degree[node.id] ?: 0) >= tuning.labelMinDegree ||
            context.zoom >= tuning.labelZoomThreshold ||
            nodeCount <= tuning.labelAllUnderNodes
    }

    private fun decide(context: MemoryLabelContext, chipOf: (MemoryGraphNode) -> Rect?) {
        shown.clear()
        placedCount = 0
        order.forEachIndexed { index, node -> if (isFocus(node, context)) settle(index, node, chipOf(node), always = true) }
        order.forEachIndexed { index, node ->
            if (isFocus(node, context)) return@forEachIndexed
            if (!isCandidate(node, context)) return@forEachIndexed forget(index)
            val chip = chipOf(node) ?: return@forEachIndexed forget(index)
            val always = node.kind == MemoryGraphNodeKind.Backend || !context.tuning.labelCulling
            settle(index, node, chip, always || keeps(index, chip, context.tuning))
        }
    }

    /**
     * Whether the label at [index] shows after this decision: placed at once if it just became a
     * candidate and is clear; otherwise it changes only after [MemoryGraphTuning.labelHysteresisCount]
     * decisions running past the margin.
     */
    private fun keeps(index: Int, chip: Rect, tuning: MemoryGraphTuning): Boolean {
        val margin = tuning.labelHysteresisMargin
        val count = tuning.labelHysteresisCount.coerceAtLeast(1f)
        if (!wasCandidate[index]) return !overlapsPlaced(chip)
        if (wasShown[index]) {
            overlapStreak[index] = if (overlapsPlaced(chip.deflate(margin))) overlapStreak[index] + 1 else 0
            return overlapStreak[index] < count
        }
        clearStreak[index] = if (overlapsPlaced(chip.inflate(margin))) 0 else clearStreak[index] + 1
        return clearStreak[index] >= count
    }

    private fun settle(index: Int, node: MemoryGraphNode, chip: Rect?, always: Boolean) {
        wasCandidate[index] = true
        val showing = always && chip != null
        if (showing != wasShown[index]) {
            overlapStreak[index] = 0
            clearStreak[index] = 0
        }
        wasShown[index] = showing
        if (!showing || chip == null) return
        shown += node.id
        if (placedCount * RECT_FLOATS < placed.size) {
            val at = placedCount * RECT_FLOATS
            placed[at] = chip.left
            placed[at + 1] = chip.top
            placed[at + 2] = chip.right
            placed[at + 3] = chip.bottom
            placedCount++
        }
    }

    private fun forget(index: Int) {
        wasCandidate[index] = false
        wasShown[index] = false
        overlapStreak[index] = 0
        clearStreak[index] = 0
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

/**
 * Each label's opacity, eased toward shown (1) or hidden (0) over a duration instead of popping; a
 * zero duration makes every change instant (reduced motion). Each frame [advance]s it by the time
 * since the last; while anything is still fading, [frameNanos] ticks every frame so the canvas
 * redraws, then the clock parks.
 */
@Stable
internal class MemoryLabelFade {
    private val alphas = HashMap<String, Float>()
    private var lastNanos = 0L
    private var wasFading = false
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val clock = TimeSource.Monotonic.markNow()

    /** How long a label takes to fade in or out; 0 is instant (reduced motion). */
    var durationNanos: Long = DEFAULT_DURATION_NANOS

    /** [advance] on this fade's own monotonic clock and [durationNanos]. */
    fun advance(shown: Set<String>): Boolean = advance(shown, clock.elapsedNow().inWholeNanoseconds, durationNanos)

    /** The frame clock the canvas reads while labels fade, so it redraws each frame until they settle. */
    var frameNanos by mutableLongStateOf(0L)
        private set

    /** [id]'s opacity this frame. */
    fun alphaOf(id: String): Float = alphas[id] ?: 0f

    /** Every node still partly visible: the shown ones and those fading out. */
    fun visible(): Set<String> = alphas.keys

    /**
     * Move every opacity toward [shown] by the time since the last fading frame ([nowNanos] on a
     * monotonic clock; a fade that is just starting moves one frame's worth); returns whether any is
     * still fading.
     */
    fun advance(shown: Set<String>, nowNanos: Long, durationNanos: Long): Boolean {
        val instant = durationNanos <= 0L
        val elapsed = if (wasFading) nowNanos - lastNanos else FRAME_NANOS
        val step = if (instant) 1f else elapsed.toFloat() / durationNanos
        lastNanos = nowNanos
        shown.forEach { id -> if (id !in alphas) alphas[id] = if (instant || !wasStarted) 1f else 0f }
        wasStarted = true
        var fading = false
        val iterator = alphas.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = if (entry.key in shown) 1f else 0f
            val next = when {
                target > entry.value -> (entry.value + step).coerceAtMost(1f)
                target < entry.value -> (entry.value - step).coerceAtLeast(0f)
                else -> target
            }
            if (next == 0f && target == 0f) iterator.remove() else entry.setValue(next)
            if (next != target) fading = true
        }
        wasFading = fading
        if (fading) wakes.trySend(Unit)
        return fading
    }

    /** The first frame shows its labels at once; only later changes fade. */
    private var wasStarted = false

    /** Ticks [frameNanos] each frame while labels fade; parks between fades. Runs for the canvas's lifetime. */
    suspend fun run() {
        while (true) {
            wakes.receive()
            withFrameNanos { now -> frameNanos = now }
        }
    }

    companion object {
        private const val FRAME_NANOS = 16_666_667L

        /** A short fade: long enough to read as a fade, short enough not to lag a hover. */
        const val DEFAULT_DURATION_NANOS = 160_000_000L
    }
}
