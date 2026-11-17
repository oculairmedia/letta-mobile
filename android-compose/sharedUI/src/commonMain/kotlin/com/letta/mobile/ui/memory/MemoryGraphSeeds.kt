package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a graph with several agents (the fleet view) starts: agents on a sunflower spiral around
 * the backend at the centre (a compact disc the forces open out, not a ring they cannot close), and
 * every other node near the centroid of the agents it links to, jittered by a hash of its id so the
 * same graph always starts the same way. A node that
 * arrives later starts near its agents as already shown. One agent's star never seeds this way.
 */
internal object MemoryGraphSeeds {
    /** Whether [view] seeds under [tuning]: seeding on and two or more agents. */
    fun applies(view: MemoryGraphView, tuning: MemoryGraphTuning): Boolean =
        tuning.seededLayout && view.nodes.count { it.kind == MemoryGraphNodeKind.Agent } >= 2

    /** Every node's first position. */
    fun initial(view: MemoryGraphView, tuning: MemoryGraphTuning): Map<String, MemoryGraphPoint> {
        val agents = view.nodes.filter { it.kind == MemoryGraphNodeKind.Agent }
        val placed = LinkedHashMap<String, MemoryGraphPoint>(view.nodes.size)
        agents.forEachIndexed { rank, agent ->
            val radius = tuning.seedSpacing * sqrt(rank + HALF)
            val angle = rank * GOLDEN_ANGLE
            placed[agent.id] = MemoryGraphPoint((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
        }
        val owners = ownersOf(view)
        view.nodes.forEach { node ->
            if (node.id in placed) return@forEach
            placed[node.id] = if (node.kind == MemoryGraphNodeKind.Backend) {
                ORIGIN
            } else {
                near(owners[node.id].orEmpty(), placed::get, node.id, tuning.seedJitter) ?: jittered(ORIGIN, node.id, tuning.seedJitter)
            }
        }
        return placed
    }

    /** Each node's owning agents: the agents it links to (agents and the backend own nothing). */
    fun ownersOf(view: MemoryGraphView): Map<String, List<String>> {
        val kinds = view.nodes.associate { it.id to it.kind }
        val owners = HashMap<String, MutableList<String>>()
        fun own(node: String, owner: String) {
            val kind = kinds[node] ?: return
            val owned = kind != MemoryGraphNodeKind.Agent && kind != MemoryGraphNodeKind.Backend
            if (owned && kinds[owner] == MemoryGraphNodeKind.Agent) owners.getOrPut(node) { mutableListOf() } += owner
        }
        view.edges.forEach { edge ->
            own(edge.fromId, edge.toId)
            own(edge.toId, edge.fromId)
        }
        return owners
    }

    /** Near the centroid of [owners] placed at [positionOf], jittered by [id]; null when none is placed. */
    fun near(owners: List<String>, positionOf: (String) -> MemoryGraphPoint?, id: String, jitter: Float): MemoryGraphPoint? {
        val points = owners.mapNotNull(positionOf)
        if (points.isEmpty()) return null
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        val centroid = MemoryGraphPoint(cx, cy)
        return jittered(centroid, id, jitter)
    }

    private fun jittered(centre: MemoryGraphPoint, id: String, jitter: Float): MemoryGraphPoint {
        val hash = stableHash(id)
        val angle = 2.0 * PI * (hash and HALF_MASK) / HALF_RANGE
        val distance = jitter * ((hash ushr HALF_BITS) and HALF_MASK) / HALF_RANGE.toFloat()
        return MemoryGraphPoint(centre.x + (distance * cos(angle)).toFloat(), centre.y + (distance * sin(angle)).toFloat())
    }

    /** FNV-1a over the id's characters: the same on every platform, unlike [String.hashCode]'s contract. */
    private fun stableHash(id: String): Int {
        var hash = FNV_OFFSET
        id.forEach { char -> hash = (hash xor char.code) * FNV_PRIME }
        return hash
    }

    private val ORIGIN = MemoryGraphPoint(0f, 0f)
    private const val HALF = 0.5f

    /** The sunflower's turn per rank: the golden angle, so no two agents line up along a spoke. */
    private val GOLDEN_ANGLE = PI * (3.0 - kotlin.math.sqrt(5.0))
    private const val FNV_OFFSET = -2128831035
    private const val FNV_PRIME = 16777619
    private const val HALF_BITS = 16
    private const val HALF_MASK = 0xFFFF
    private const val HALF_RANGE = 65536.0
}
