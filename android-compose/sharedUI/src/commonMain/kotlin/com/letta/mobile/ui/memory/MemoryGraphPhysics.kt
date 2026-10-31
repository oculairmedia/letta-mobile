package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.graph.MemoryGraphBounds
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutConfig
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphView
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The live half of the memory graph layout. The controller's deterministic force layout is the
 * target; this runs the same forces (pairwise repulsion, edge springs, a weak pull to the origin)
 * frame by frame so the graph can bloom open, follow a dragged node and settle again. It uses the
 * controller's tuning, so a settled graph sits where the controller put it unless the user moved
 * something. Collision by drawn radius keeps big nodes from overlapping.
 *
 * [isHot] turns false once every node is nearly still (or after [MAX_TICKS_PER_HEAT]); callers
 * stop ticking then, so a settled graph costs nothing per frame. With [motion] off (reduced motion
 * or a graph too big to simulate) nothing ever heats: a dragged node simply moves alone.
 */
internal class MemoryGraphPhysics private constructor(
    private val ids: List<String>,
    private val radii: FloatArray,
    private val springs: IntArray,
    private val motion: Boolean,
) {
    private val n = ids.size
    private val indexById = ids.withIndex().associate { (index, id) -> id to index }
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val fx = FloatArray(n)
    private val fy = FloatArray(n)
    private var pinned = -1
    private var ticksSinceHeat = 0
    private val config = MemoryGraphLayoutConfig()

    var isHot: Boolean = false
        private set

    /** Wake the simulation, for a bloom, a drag or new nodes. A no-op without [motion]. */
    fun heat() {
        if (!motion || n < 2) return
        isHot = true
        ticksSinceHeat = 0
    }

    /** Hold [id] at [point] (layout space) until [release]; neighbours follow while it is hot. */
    fun pin(id: String, point: MemoryGraphPoint) {
        val index = indexById[id] ?: return
        pinned = index
        x[index] = point.x
        y[index] = point.y
        vx[index] = 0f
        vy[index] = 0f
        heat()
    }

    fun release() {
        pinned = -1
    }

    /** One integration step. Returns whether the graph is still moving. */
    fun tick(): Boolean {
        if (!isHot) return false
        fx.fill(0f)
        fy.fill(0f)
        applyRepulsion()
        applySprings()
        val fastest = integrate()
        ticksSinceHeat++
        isHot = fastest > SETTLE_SPEED && ticksSinceHeat < MAX_TICKS_PER_HEAT
        return isHot
    }

    fun snapshot(): MemoryGraphLayout {
        val positions = LinkedHashMap<String, MemoryGraphPoint>(n)
        ids.forEachIndexed { index, id -> positions[id] = MemoryGraphPoint(x[index], y[index]) }
        return MemoryGraphLayout(positions = positions, bounds = MemoryGraphBounds.of(positions.values))
    }

    private fun applyRepulsion() {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                if (dx == 0f && dy == 0f) {
                    dx = (j - i) * COINCIDENT_NUDGE
                    dy = (i + 1) * COINCIDENT_NUDGE
                }
                val distance = max(sqrt(dx * dx + dy * dy), MIN_DISTANCE)
                val clearance = radii[i] + radii[j] + COLLISION_PADDING
                val overlap = if (distance < clearance) (clearance - distance) * COLLISION_STRENGTH else 0f
                val force = config.repulsion / max(distance * distance, 1f) + overlap
                addPair(i, j, dx / distance * force, dy / distance * force)
            }
        }
    }

    private fun applySprings() {
        var s = 0
        while (s < springs.size) {
            val a = springs[s]
            val b = springs[s + 1]
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val distance = max(sqrt(dx * dx + dy * dy), MIN_DISTANCE)
            val force = config.attraction * (distance - config.idealEdgeLength)
            addPair(a, b, dx / distance * force, dy / distance * force)
            s += 2
        }
    }

    private fun addPair(a: Int, b: Int, px: Float, py: Float) {
        fx[a] += px
        fy[a] += py
        fx[b] -= px
        fy[b] -= py
    }

    /** Damped step with a per-step cap; returns the fastest free node's speed. */
    private fun integrate(): Float {
        var fastest = 0f
        for (i in 0 until n) {
            if (i == pinned) continue
            vx[i] = (vx[i] + fx[i] - x[i] * config.gravity) * config.damping
            vy[i] = (vy[i] + fy[i] - y[i] * config.gravity) * config.damping
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val scale = if (speed > config.maxStep) config.maxStep / speed else 1f
            x[i] += vx[i] * scale
            y[i] += vy[i] * scale
            fastest = max(fastest, speed * scale)
        }
        return fastest
    }

    companion object {
        /** Above this many nodes the O(n²) step is not run per frame: the graph is static, drag moves one node. */
        const val LIVE_NODE_LIMIT = 400

        /** Where a bloom starts: the target layout shrunk toward its centre by this factor. */
        const val BLOOM_START_SCALE = 0.35f

        /**
         * A simulation over [view] starting from [previous] positions where a node already had one
         * (a reload or filter keeps what the user sees), else from [target] — shrunk toward its
         * centre when [bloom], so the graph opens out. [radiusOf] is each node's drawn radius.
         */
        fun create(
            view: MemoryGraphView,
            target: MemoryGraphLayout,
            radiusOf: (String) -> Float,
            start: MemoryGraphPhysicsStart,
        ): MemoryGraphPhysics {
            val ids = view.nodes.map { it.id }
            val index = ids.withIndex().associate { (i, id) -> id to i }
            val springs = view.edges.flatMap { edge -> springOf(index[edge.fromId], index[edge.toId]) }.toIntArray()
            val motion = start.motion && ids.size <= LIVE_NODE_LIMIT
            val physics = MemoryGraphPhysics(ids, FloatArray(ids.size) { radiusOf(ids[it]) }, springs, motion)
            val moved = physics.seed(target, start)
            if (moved) physics.heat()
            return physics
        }
    }

    /** Place every node; returns whether anything starts away from the target (so should move). */
    private fun seed(target: MemoryGraphLayout, start: MemoryGraphPhysicsStart): Boolean {
        val centre = target.bounds.center
        val bloom = start.bloom && motion && start.previous == null
        var moved = bloom
        ids.forEachIndexed { index, id ->
            val goal = target[id] ?: centre
            val point = start.previous?.get(id) ?: if (bloom) shrink(goal, centre) else goal
            if (start.previous != null && start.previous[id] == null) moved = true
            x[index] = point.x
            y[index] = point.y
        }
        return moved
    }

    private fun shrink(point: MemoryGraphPoint, centre: MemoryGraphPoint) = MemoryGraphPoint(
        centre.x + (point.x - centre.x) * BLOOM_START_SCALE,
        centre.y + (point.y - centre.y) * BLOOM_START_SCALE,
    )
}

/**
 * How a new simulation starts: [previous] positions to keep (null on a first open), whether to
 * [bloom] open, and whether anything may move at all ([motion] false under reduced motion).
 */
internal data class MemoryGraphPhysicsStart(
    val previous: MemoryGraphLayout?,
    val bloom: Boolean,
    val motion: Boolean,
)

/** An edge's two node indices, or nothing for a self-loop or an edge to a node not in the view. */
private fun springOf(from: Int?, to: Int?): List<Int> = listOfNotNull(from, to).takeIf { it.size == 2 && from != to }.orEmpty()

private const val SETTLE_SPEED = 0.04f
private const val MAX_TICKS_PER_HEAT = 360
private const val MIN_DISTANCE = 0.01f
private const val COINCIDENT_NUDGE = 0.5f
private const val COLLISION_PADDING = 10f
private const val COLLISION_STRENGTH = 0.6f
