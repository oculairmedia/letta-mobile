package com.letta.mobile.data.memory.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radial tree seed: the best-connected node sits at the origin, every other
 * node goes on a ring by its BFS depth from it, ordered by kind then by list
 * order. Unreachable nodes share one outer ring. Fully deterministic.
 */
internal object MemoryGraphRadialSeed {
    fun place(view: MemoryGraphView, config: MemoryGraphLayoutConfig): Map<String, MemoryGraphPoint> {
        val ids = view.nodes.map { it.id }
        val root = ids.maxByOrNull { view.degreeOf(it) } ?: return emptyMap()
        val depthById = bfsDepths(root, adjacency(view))
        val unreachableDepth = (depthById.values.maxOrNull() ?: 0) + 1
        val rings = ids.filter { it != root }
            .groupBy { depthById[it] ?: unreachableDepth }
            .entries
            .sortedBy { it.key }
        val kindOrder = view.nodes.associate { it.id to it.kind.ordinal }
        val positions = LinkedHashMap<String, MemoryGraphPoint>()
        positions[root] = MemoryGraphPoint(0f, 0f)
        rings.forEach { (depth, members) ->
            val ordered = members.sortedBy { kindOrder[it] ?: 0 }
            placeRing(ordered, depth, config, positions)
        }
        return ids.associateWith { positions.getValue(it) }
    }

    private fun placeRing(
        members: List<String>,
        depth: Int,
        config: MemoryGraphLayoutConfig,
        into: MutableMap<String, MemoryGraphPoint>,
    ) {
        val count = members.size
        val radius = max(config.ringSpacing * depth, count * config.minArcLength / (2f * PI.toFloat()))
        val phase = depth * RING_PHASE_STEP
        members.forEachIndexed { index, id ->
            val angle = phase + 2.0 * PI * index / count
            into[id] = MemoryGraphPoint((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
        }
    }

    private fun adjacency(view: MemoryGraphView): Map<String, List<String>> {
        val neighbours = HashMap<String, MutableList<String>>()
        view.edges.forEach { edge ->
            neighbours.getOrPut(edge.fromId) { mutableListOf() } += edge.toId
            neighbours.getOrPut(edge.toId) { mutableListOf() } += edge.fromId
        }
        return neighbours
    }

    private fun bfsDepths(root: String, adjacency: Map<String, List<String>>): Map<String, Int> {
        val depth = HashMap<String, Int>()
        depth[root] = 0
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val next = depth.getValue(current) + 1
            adjacency[current].orEmpty().forEach { neighbour ->
                if (neighbour !in depth) {
                    depth[neighbour] = next
                    queue.addLast(neighbour)
                }
            }
        }
        return depth
    }

    private const val RING_PHASE_STEP = 0.5
}

/**
 * Velocity-Verlet-free force relaxation of a whole graph: [MemoryGraphForceField] stepped a
 * budgeted number of times from a seed. Iterations shrink with n squared to respect the work budget.
 */
internal class MemoryGraphForceSimulation(
    view: MemoryGraphView,
    private val config: MemoryGraphLayoutConfig,
) {
    private val ids = view.nodes.map { it.id }
    private val field = MemoryGraphForceField(ids.size, MemoryGraphForceField.springsOf(view, ids), config)

    fun relax(seed: Map<String, MemoryGraphPoint>): Map<String, MemoryGraphPoint> {
        ids.forEachIndexed { index, id -> field.place(index, seed.getValue(id)) }
        repeat(iterationCount()) { field.step() }
        return ids.withIndex().associate { (index, id) -> id to field.point(index) }
    }

    private fun iterationCount(): Int {
        val n = ids.size
        if (n < 2) return 0
        val budgeted = config.workBudget / (n * n)
        return min(config.iterations, max(config.minIterations, budgeted))
    }
}

/**
 * The memory graph's force model, one step at a time: pairwise repulsion, spring attraction along
 * edges toward [MemoryGraphLayoutConfig.idealEdgeLength], a weak pull to the origin so disconnected
 * pieces stay near, damped velocity and a per-step cap. Given [radii] (each node's drawn radius) it
 * also pushes apart nodes whose circles overlap.
 *
 * The one implementation of those forces: the controller's deterministic layout
 * ([MemoryGraphForceSimulation]) relaxes it to a fixed point, and the memory page's live view steps
 * it frame by frame so the graph can bloom, follow a drag and settle again.
 */
class MemoryGraphForceField(
    /** How many nodes, indexed 0 until [size]. */
    val size: Int,
    /** Edge endpoints as index pairs, flattened (a0, b0, a1, b1, ...); see [springsOf]. */
    private val springs: IntArray,
    private val config: MemoryGraphLayoutConfig = MemoryGraphLayoutConfig(),
    /** Each node's collision radius, or null for point masses. */
    private val radii: FloatArray? = null,
) {
    private val x = FloatArray(size)
    private val y = FloatArray(size)
    private val vx = FloatArray(size)
    private val vy = FloatArray(size)
    private val fx = FloatArray(size)
    private val fy = FloatArray(size)

    /** Node [index]'s position. */
    fun point(index: Int): MemoryGraphPoint = MemoryGraphPoint(x[index], y[index])

    /** Put node [index] at [point]; with [stop], also drop its velocity (a drag holds it there). */
    fun place(index: Int, point: MemoryGraphPoint, stop: Boolean = false) {
        x[index] = point.x
        y[index] = point.y
        if (stop) {
            vx[index] = 0f
            vy[index] = 0f
        }
    }

    /** One step. Node [pinned] (or none, [FREE]) stays where it is. Returns the fastest free node's speed. */
    fun step(pinned: Int = FREE): Float {
        fx.fill(0f)
        fy.fill(0f)
        applyRepulsion()
        applySprings()
        return integrate(pinned)
    }

    private fun applyRepulsion() {
        for (i in 0 until size) {
            for (j in i + 1 until size) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                if (dx == 0f && dy == 0f) {
                    // Coincident seeds: separate along a deterministic axis.
                    dx = (j - i) * COINCIDENT_NUDGE
                    dy = (i + 1) * COINCIDENT_NUDGE
                }
                val distanceSq = max(dx * dx + dy * dy, MIN_DISTANCE_SQ)
                val distance = sqrt(distanceSq)
                val force = config.repulsion / distanceSq + overlap(i, j, distance)
                addPair(i, j, dx / distance * force, dy / distance * force)
            }
        }
    }

    /** How hard two drawn circles push apart where they overlap; 0 for point masses. */
    private fun overlap(i: Int, j: Int, distance: Float): Float {
        val r = radii ?: return 0f
        val clearance = r[i] + r[j] + COLLISION_PADDING
        return if (distance < clearance) (clearance - distance) * COLLISION_STRENGTH else 0f
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
    private fun integrate(pinned: Int): Float {
        var fastest = 0f
        for (i in 0 until size) {
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
        /** No node is pinned. */
        const val FREE = -1

        /** [view]'s edges as index pairs over [ids], skipping self-loops and edges to nodes not in [ids]. */
        fun springsOf(view: MemoryGraphView, ids: List<String>): IntArray {
            val indexById = ids.withIndex().associate { (index, id) -> id to index }
            val pairs = ArrayList<Int>(view.edges.size * 2)
            view.edges.forEach { edge ->
                val from = indexById[edge.fromId]
                val to = indexById[edge.toId]
                if (from != null && to != null && from != to) {
                    pairs += from
                    pairs += to
                }
            }
            return pairs.toIntArray()
        }

        private const val MIN_DISTANCE = 0.01f
        private const val MIN_DISTANCE_SQ = 1f
        private const val COINCIDENT_NUDGE = 0.5f
        private const val COLLISION_PADDING = 10f
        private const val COLLISION_STRENGTH = 0.6f
    }
}
