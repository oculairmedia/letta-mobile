package com.letta.mobile.data.memory.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    private val field = MemoryGraphForceField(ids.size, MemoryGraphForceField.springsOf(view), config)

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
