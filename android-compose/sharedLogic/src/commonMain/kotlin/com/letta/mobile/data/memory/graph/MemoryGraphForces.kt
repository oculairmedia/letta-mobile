package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

    // What [applyTuning] can change; the defaults are [config]'s, so an untuned field (the
    // controller's layout) steps exactly as it always has.
    private var repulsion = config.repulsion
    private var cutoffSq = 0f
    private val charge = FloatArray(size) { 1f }
    private val restLength = FloatArray(springs.size / 2) { config.idealEdgeLength }
    private val stiffness = FloatArray(springs.size / 2) { config.attraction }

    // Each spring's share of its pull on its source (a) and target (b): 1 and 1 untuned, split
    // toward the lower-degree end under degree scaling so hubs move less.
    private val sourceWeight = FloatArray(springs.size / 2) { 1f }
    private val targetWeight = FloatArray(springs.size / 2) { 1f }

    // Cluster force: each node's owning agents as a compressed list ([ownerStart] indexes
    // [owners]); none untuned.
    private var clusterStrength = 0f
    private var ownerStart = IntArray(size + 1)
    private var owners = IntArray(0)
    private var gravity = config.gravity
    private var collisionPadding = COLLISION_PADDING
    private var collisionStrength = COLLISION_STRENGTH

    /**
     * Applies [tuning]'s forces: [nodeKinds] holds each node's [MemoryGraphNodeKind] ordinal (its
     * charge) and [springKinds] each spring's [MemoryGraphEdgeKind] ordinal (its rest length and
     * stiffness), in the orders the field was built with (see [springKindsOf]).
     */
    fun applyTuning(tuning: MemoryGraphTuning, nodeKinds: IntArray, springKinds: IntArray) {
        repulsion = tuning.repulsion
        cutoffSq = tuning.repulsionCutoff * tuning.repulsionCutoff
        val nodeKindValues = MemoryGraphNodeKind.entries
        for (i in 0 until min(size, nodeKinds.size)) charge[i] = tuning.chargeOf(nodeKindValues[nodeKinds[i]])
        applySpringTuning(tuning, springKinds)
        applyClusterTuning(tuning, nodeKinds)
        gravity = tuning.gravity
        collisionPadding = tuning.collisionPadding
        collisionStrength = tuning.collisionStrength
    }

    /**
     * Each spring's rest length and stiffness for its kind, the stiffness scaled by
     * 1 / min(degree of its ends) and its pull split by degree (d3-force's forceLink), blended in by
     * [MemoryGraphTuning.degreeScaling]. Degrees are counted here, once per graph.
     */
    private fun applySpringTuning(tuning: MemoryGraphTuning, springKinds: IntArray) {
        val degree = IntArray(size)
        springs.forEach { degree[it]++ }
        val blend = tuning.degreeScaling.coerceIn(0f, 1f)
        val edgeKindValues = MemoryGraphEdgeKind.entries
        for (s in 0 until min(restLength.size, springKinds.size)) {
            val kind = edgeKindValues[springKinds[s]]
            val a = degree[springs[2 * s]].coerceAtLeast(1)
            val b = degree[springs[2 * s + 1]].coerceAtLeast(1)
            val bias = a.toFloat() / (a + b)
            restLength[s] = tuning.lengthOf(kind)
            stiffness[s] = tuning.stiffnessOf(kind) * lerp(1f, 1f / min(a, b), blend)
            sourceWeight[s] = lerp(1f, 2f * (1f - bias), blend)
            targetWeight[s] = lerp(1f, 2f * bias, blend)
        }
    }

    /**
     * The cluster force's owners: each node linked to agents (a label group or block) is owned by
     * them. Only a graph with two or more agents (the fleet view) clusters, so one agent's star is
     * left as it is.
     */
    private fun applyClusterTuning(tuning: MemoryGraphTuning, nodeKinds: IntArray) {
        val agent = MemoryGraphNodeKind.Agent.ordinal
        val backend = MemoryGraphNodeKind.Backend.ordinal
        val agents = nodeKinds.count { it == agent }
        clusterStrength = if (tuning.clusterEnabled && agents >= 2) tuning.clusterStrength else 0f
        val owned = Array(size) { mutableListOf<Int>() }
        fun own(node: Int, owner: Int) {
            val kind = nodeKinds.getOrNull(node) ?: return
            if (kind != agent && kind != backend && nodeKinds.getOrNull(owner) == agent) owned[node] += owner
        }
        var s = 0
        while (s < springs.size) {
            own(springs[s], springs[s + 1])
            own(springs[s + 1], springs[s])
            s += 2
        }
        ownerStart = IntArray(size + 1)
        for (i in 0 until size) ownerStart[i + 1] = ownerStart[i] + owned[i].size
        owners = owned.flatMap { it }.toIntArray()
    }

    /** Node [index]'s position. */
    fun point(index: Int): MemoryGraphPoint = MemoryGraphPoint(x[index], y[index])

    /** Put node [index] at [point]. */
    fun place(index: Int, point: MemoryGraphPoint) {
        x[index] = point.x
        y[index] = point.y
    }

    /** Put node [index] at [point] and stop it there (a drag holding it). */
    fun hold(index: Int, point: MemoryGraphPoint) {
        place(index, point)
        vx[index] = 0f
        vy[index] = 0f
    }

    /** One step. Node [pinned] (or none, [FREE]) stays where it is. Returns the fastest free node's speed. */
    fun step(pinned: Int = FREE): Float {
        applyForces()
        return integrate(pinned)
    }

    /**
     * One live step under [motion]: the forces scaled by the cooling [alpha] (1 when hot, decaying
     * toward [MemoryGraphTuning.alphaMin]), [MemoryGraphMotion.damping] friction and a speed cap.
     * At [MemoryGraphMotion.forGraph]'s small-graph tuning and alpha 1 it is exactly [step].
     */
    fun step(pinned: Int, motion: MemoryGraphMotion, alpha: Float): Float {
        applyForces()
        applyCluster()
        return integrateLive(pinned, motion, alpha)
    }

    /** Pulls each owned node toward its owners' centroid ([MemoryGraphTuning.clusterStrength]). */
    private fun applyCluster() {
        if (clusterStrength == 0f) return
        for (i in 0 until size) {
            val from = ownerStart[i]
            val to = ownerStart[i + 1]
            if (from == to) continue
            var cx = 0f
            var cy = 0f
            for (k in from until to) {
                cx += x[owners[k]]
                cy += y[owners[k]]
            }
            val count = (to - from).toFloat()
            fx[i] += (cx / count - x[i]) * clusterStrength
            fy[i] += (cy / count - y[i]) * clusterStrength
        }
    }

    private fun applyForces() {
        fx.fill(0f)
        fy.fill(0f)
        applyRepulsion()
        applySprings()
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
                // Drawn circles that overlap push apart harder; point masses have no radii.
                val overlap = radii?.let { r -> max(0f, r[i] + r[j] + collisionPadding - distance) * collisionStrength } ?: 0f
                val repels = cutoffSq == 0f || distanceSq <= cutoffSq
                val force = (if (repels) repulsion * (charge[i] * charge[j]) / distanceSq else 0f) + overlap
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
        var s = 0
        while (s < springs.size) {
            val a = springs[s]
            val b = springs[s + 1]
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val distance = max(sqrt(dx * dx + dy * dy), MIN_DISTANCE)
            val spring = s / 2
            val force = stiffness[spring] * (distance - restLength[spring])
            val px = dx / distance * force
            val py = dy / distance * force
            fx[a] += px * sourceWeight[spring]
            fy[a] += py * sourceWeight[spring]
            fx[b] -= px * targetWeight[spring]
            fy[b] -= py * targetWeight[spring]
            s += 2
        }
    }

    /** Damped step with a per-step cap; returns the fastest free node's speed. */
    private fun integrate(pinned: Int): Float {
        var fastest = 0f
        for (i in 0 until size) {
            if (i == pinned) continue
            vx[i] = (vx[i] + fx[i] - x[i] * gravity) * config.damping
            vy[i] = (vy[i] + fy[i] - y[i] * gravity) * config.damping
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val scale = if (speed > config.maxStep) config.maxStep / speed else 1f
            x[i] += vx[i] * scale
            y[i] += vy[i] * scale
            fastest = max(fastest, speed * scale)
        }
        return fastest
    }

    /** [integrate] under live [motion]: cooled forces, its friction, and (dense graphs) a velocity cap. */
    private fun integrateLive(pinned: Int, motion: MemoryGraphMotion, alpha: Float): Float {
        var fastest = 0f
        for (i in 0 until size) {
            if (i == pinned) continue
            vx[i] = (vx[i] + (fx[i] - x[i] * gravity) * alpha) * motion.damping
            vy[i] = (vy[i] + (fy[i] - y[i] * gravity) * alpha) * motion.damping
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val scale = if (speed > motion.maxSpeed) motion.maxSpeed / speed else 1f
            if (motion.clampVelocity) {
                // A capped hub keeps the capped speed, so it cannot carry momentum back past its rest point.
                vx[i] *= scale
                vy[i] *= scale
            }
            val moved = if (motion.clampVelocity) 1f else scale
            x[i] += vx[i] * moved
            y[i] += vy[i] * moved
            fastest = max(fastest, speed * scale)
        }
        return fastest
    }

    companion object {
        /** No node is pinned. */
        const val FREE = -1

        /** [view]'s edges as index pairs over its nodes, skipping self-loops and edges to nodes not in it. */
        fun springsOf(view: MemoryGraphView): IntArray {
            val indexById = view.nodes.withIndex().associate { (index, node) -> node.id to index }
            val pairs = ArrayList<Int>(view.edges.size * 2)
            view.edges.forEach { edge ->
                val ends = listOfNotNull(indexById[edge.fromId], indexById[edge.toId]).distinct()
                if (ends.size == 2) pairs += ends
            }
            return pairs.toIntArray()
        }

        /** Each of [springsOf]'s springs' [MemoryGraphEdgeKind] ordinal, in the same order. */
        fun springKindsOf(view: MemoryGraphView): IntArray {
            val indexById = view.nodes.withIndex().associate { (index, node) -> node.id to index }
            val kinds = ArrayList<Int>(view.edges.size)
            view.edges.forEach { edge ->
                val ends = listOfNotNull(indexById[edge.fromId], indexById[edge.toId]).distinct()
                if (ends.size == 2) kinds += edge.kind.ordinal
            }
            return kinds.toIntArray()
        }

        private const val MIN_DISTANCE = 0.01f
        private const val MIN_DISTANCE_SQ = 1f
        private const val COINCIDENT_NUDGE = 0.5f
        private const val COLLISION_PADDING = 10f
        private const val COLLISION_STRENGTH = 0.6f
    }
}

/**
 * How the memory page's live view steps a [MemoryGraphForceField], tuned by how crowded the graph
 * is ([MemoryGraphTuning]'s thresholds). A sparse graph (one agent's star) keeps the sparse
 * friction and per-step cap and never cools, so it moves as it always has; a crowded one (the fleet
 * view: hundreds of label groups and hubs shared by every agent) gets more friction, a lower speed
 * cap that holds velocity too, and d3-force style cooling: alpha decays each step and scales the
 * forces, and the view stops once it falls below [MemoryGraphTuning.alphaMin].
 */
@Immutable
data class MemoryGraphMotion(
    /** Velocity kept per step (1 minus friction). */
    val damping: Float,
    /** The fastest a node moves per step. */
    val maxSpeed: Float,
    /** The share of alpha lost per step; 0 never cools. */
    val alphaDecay: Float,
    /** Whether the cap also limits the velocity carried into the next step, not just this step's move. */
    val clampVelocity: Boolean,
) {
    /** Alpha after one more step. */
    fun cool(alpha: Float): Float = alpha * (1f - alphaDecay)

    companion object {
        /**
         * The motion for a graph of [nodes] nodes and [edges] edges under [tuning]. Crowding counts
         * every node plus twice each edge beyond a tree's, so a hub-heavy graph tunes as crowded
         * sooner than a star.
         */
        fun forGraph(nodes: Int, edges: Int, tuning: MemoryGraphTuning = MemoryGraphTuning()): MemoryGraphMotion {
            val crowding = nodes + 2 * (edges - nodes).coerceAtLeast(0)
            val span = (tuning.crowdedCrowding - tuning.sparseCrowding).coerceAtLeast(1f)
            val t = ((crowding - tuning.sparseCrowding) / span).coerceIn(0f, 1f)
            if (t == 0f) return MemoryGraphMotion(tuning.sparseDamping, tuning.sparseMaxSpeed, alphaDecay = 0f, clampVelocity = false)
            val coolingSteps = tuning.coolingFrames.coerceAtLeast(1f) / t
            val alphaRatio = (tuning.alphaMin / tuning.alphaStart.coerceAtLeast(tuning.alphaMin)).coerceIn(MIN_RATIO, 1f)
            return MemoryGraphMotion(
                damping = lerp(tuning.sparseDamping, tuning.crowdedDamping, t),
                maxSpeed = lerp(tuning.sparseMaxSpeed, tuning.crowdedMaxSpeed, t),
                alphaDecay = 1f - alphaRatio.toDouble().pow(1.0 / coolingSteps).toFloat(),
                clampVelocity = true,
            )
        }

        private const val MIN_RATIO = 1e-9f
    }
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
