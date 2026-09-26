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
 * Velocity-Verlet-free force relaxation: pairwise repulsion, spring
 * attraction along edges toward [MemoryGraphLayoutConfig.idealEdgeLength], a
 * weak pull to the origin so disconnected pieces stay near, damped velocity and
 * a per-step cap. Iterations shrink with n² to respect the work budget.
 */
internal class MemoryGraphForceSimulation(
    view: MemoryGraphView,
    private val config: MemoryGraphLayoutConfig,
) {
    private val ids = view.nodes.map { it.id }
    private val indexById = ids.withIndex().associate { (index, id) -> id to index }
    private val springs = view.edges.mapNotNull { edge ->
        val from = indexById[edge.fromId]
        val to = indexById[edge.toId]
        if (from != null && to != null && from != to) from to to else null
    }
    private val n = ids.size
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val fx = FloatArray(n)
    private val fy = FloatArray(n)

    fun relax(seed: Map<String, MemoryGraphPoint>): Map<String, MemoryGraphPoint> {
        ids.forEachIndexed { index, id ->
            val point = seed.getValue(id)
            x[index] = point.x
            y[index] = point.y
        }
        repeat(iterationCount()) { step() }
        return ids.withIndex().associate { (index, id) -> id to MemoryGraphPoint(x[index], y[index]) }
    }

    private fun iterationCount(): Int {
        if (n < 2) return 0
        val budgeted = config.workBudget / (n * n)
        return min(config.iterations, max(config.minIterations, budgeted))
    }

    private fun step() {
        fx.fill(0f)
        fy.fill(0f)
        applyRepulsion()
        applySprings()
        integrate()
    }

    private fun applyRepulsion() {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                if (dx == 0f && dy == 0f) {
                    // Coincident seeds: separate along a deterministic axis.
                    dx = (j - i) * COINCIDENT_NUDGE
                    dy = (i + 1) * COINCIDENT_NUDGE
                }
                val distanceSq = max(dx * dx + dy * dy, MIN_DISTANCE_SQ)
                val distance = sqrt(distanceSq)
                val force = config.repulsion / distanceSq
                val px = dx / distance * force
                val py = dy / distance * force
                fx[i] += px
                fy[i] += py
                fx[j] -= px
                fy[j] -= py
            }
        }
    }

    private fun applySprings() {
        springs.forEach { (a, b) ->
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val distance = max(sqrt(dx * dx + dy * dy), MIN_DISTANCE)
            val force = config.attraction * (distance - config.idealEdgeLength)
            val px = dx / distance * force
            val py = dy / distance * force
            fx[a] += px
            fy[a] += py
            fx[b] -= px
            fy[b] -= py
        }
    }

    private fun integrate() {
        for (i in 0 until n) {
            vx[i] = (vx[i] + fx[i] - x[i] * config.gravity) * config.damping
            vy[i] = (vy[i] + fy[i] - y[i] * config.gravity) * config.damping
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val scale = if (speed > config.maxStep) config.maxStep / speed else 1f
            x[i] += vx[i] * scale
            y[i] += vy[i] * scale
        }
    }

    private companion object {
        const val MIN_DISTANCE = 0.01f
        const val MIN_DISTANCE_SQ = 1f
        const val COINCIDENT_NUDGE = 0.5f
    }
}
