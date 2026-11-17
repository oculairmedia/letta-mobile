package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.graph.MemoryGraphBounds
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphMotion
import com.letta.mobile.data.memory.graph.MemoryGraphForceField
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphView

/**
 * The live half of the memory graph layout. The controller's deterministic force layout is the
 * target; this steps the same [MemoryGraphForceField] (pairwise repulsion, edge springs, a weak
 * pull to the origin) frame by frame so the graph can bloom open, follow a dragged node and settle again. It uses the
 * controller's tuning, so a settled graph sits where the controller put it unless the user moved
 * something. Collision by drawn radius keeps big nodes from overlapping.
 *
 * Its forces and motion come from the start's [MemoryGraphTuning] (the shipped defaults unless a
 * dev tuning window changed them): the field applies its charges, springs, gravity and collision,
 * and [MemoryGraphMotion.forGraph] its friction, speed cap and cooling, so a crowded graph (the
 * fleet view) cools and comes to rest within a few seconds while a small one moves as it always has.
 *
 * [isHot] turns false once every node is nearly still, it has cooled, or after the tuning's most frames per heat; callers
 * stop ticking then, so a settled graph costs nothing per frame. With [motion] off (reduced motion
 * or a graph too big to simulate) nothing ever heats: a dragged node simply moves alone.
 */
internal class MemoryGraphPhysics private constructor(
    private val ids: List<String>,
    private val field: MemoryGraphForceField,
    start: MemoryGraphPhysicsStart,
    /** Friction, speed cap and cooling for this graph's size ([MemoryGraphMotion.forGraph]). */
    private val stepping: MemoryGraphMotion,
) {
    private val n = ids.size
    private val tuning = start.tuning
    private val motion = start.motion && n <= LIVE_NODE_LIMIT
    private val indexById = ids.withIndex().associate { (index, id) -> id to index }
    private var pinned = MemoryGraphForceField.FREE
    private var ticksSinceHeat = 0

    var isHot: Boolean = false
        private set

    /** The cooling: the tuning's start when heated, decaying each step under [stepping]; forces are scaled by it. */
    private var alpha = tuning.alphaStart

    /** The fastest node's speed in the last step (layout units per step). */
    var lastSpeed: Float = 0f
        private set

    /** Wake the simulation, for a bloom, a drag or new nodes. A no-op without [motion]. */
    fun heat() {
        if (!motion || n < 2) return
        isHot = true
        ticksSinceHeat = 0
        alpha = tuning.alphaStart
    }

    /** Hold [id] at [point] (layout space) until [release]; neighbours follow while it is hot. */
    fun pin(id: String, point: MemoryGraphPoint) {
        val index = indexById[id] ?: return
        pinned = index
        field.hold(index, point)
        heat()
    }

    fun release() {
        pinned = MemoryGraphForceField.FREE
    }

    /** One integration step. Returns whether the graph is still moving. */
    fun tick(): Boolean {
        if (!isHot) return false
        val fastest = field.step(pinned, stepping, alpha)
        lastSpeed = fastest
        alpha = stepping.cool(alpha)
        ticksSinceHeat++
        isHot = fastest > tuning.settleSpeed && ticksSinceHeat < tuning.maxFramesPerHeat && alpha >= tuning.alphaMin
        return isHot
    }

    fun snapshot(): MemoryGraphLayout {
        val positions = LinkedHashMap<String, MemoryGraphPoint>(n)
        ids.forEachIndexed { index, id -> positions[id] = field.point(index) }
        return MemoryGraphLayout(positions = positions, bounds = MemoryGraphBounds.of(positions.values))
    }

    companion object {
        /** Above this many nodes the O(n²) step is not run per frame: the graph is static, drag moves one node. */
        const val LIVE_NODE_LIMIT = 400

        /** Where a bloom starts: the target layout shrunk toward its centre by this factor. */
        const val BLOOM_START_SCALE = 0.35f

        /**
         * A simulation over [view] starting from [previous] positions where a node already had one
         * (a reload or filter keeps what the user sees), else from [target] — shrunk toward its
         * centre when [bloom], so the graph opens out. [radiusOf] is each node's drawn radius, scaled
         * by the tuning's collision radius for its kind; a [MemoryGraphPhysicsStart.reheat] heats it
         * even when nothing moved.
         */
        fun create(
            view: MemoryGraphView,
            target: MemoryGraphLayout,
            radiusOf: (String) -> Float,
            start: MemoryGraphPhysicsStart,
        ): MemoryGraphPhysics {
            val tuning = start.tuning
            val ids = view.nodes.map { it.id }
            val radii = FloatArray(ids.size) { radiusOf(ids[it]) * tuning.radiusScaleOf(view.nodes[it].kind) }
            val springs = MemoryGraphForceField.springsOf(view)
            val field = MemoryGraphForceField(ids.size, springs, radii = radii)
            field.applyTuning(tuning, IntArray(ids.size) { view.nodes[it].kind.ordinal }, MemoryGraphForceField.springKindsOf(view))
            val physics = MemoryGraphPhysics(ids, field, start, MemoryGraphMotion.forGraph(ids.size, springs.size / 2, tuning))
            val moved = physics.seed(target, start)
            if (moved || start.reheat) physics.heat()
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
            field.place(index, point)
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
 * [bloom] open, whether anything may move at all ([motion] false under reduced motion), the
 * [tuning] it runs under, and whether to [reheat] it even when nothing starts away from its place.
 */
internal data class MemoryGraphPhysicsStart(
    val previous: MemoryGraphLayout?,
    val bloom: Boolean,
    val motion: Boolean,
    val tuning: MemoryGraphTuning = MemoryGraphTuning(),
    val reheat: Boolean = false,
)
