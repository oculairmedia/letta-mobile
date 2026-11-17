package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.graph.barneshut.BarnesHutTree
import com.letta.mobile.data.memory.graph.barneshut.CollisionTerms
import com.letta.mobile.data.memory.graph.barneshut.ForceBodies
import com.letta.mobile.data.memory.graph.barneshut.ForceSums
import com.letta.mobile.data.memory.graph.barneshut.RepulsionTerms
import com.letta.mobile.data.memory.graph.forces.SpringEnds
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    private val staticMotion = MemoryGraphMotion(config.damping, config.maxStep, 0f, false)
    private val x = FloatArray(size)
    private val y = FloatArray(size)
    private val vx = FloatArray(size)
    private val vy = FloatArray(size)
    private val fx = FloatArray(size)
    private val fy = FloatArray(size)

    // Collision kept apart from the other forces: it is capped per frame and fades only at the very
    // end of the cooling, so overlaps resolve without bouncing (see [integrate]).
    private val ox = FloatArray(size)
    private val oy = FloatArray(size)
    private var collisionFadeAlpha = 0f

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

    // A node the live step never moves (the fleet's pinned backend), and the Barnes–Hut tree a big
    // graph's repulsion walks instead of every pair (null: every pair).
    private var anchor = FREE
    private var quadtree: BarnesHutTree? = null
    private var quadtreeTerms: RepulsionTerms? = null
    private val bodies = ForceBodies(x, y, charge, radii)
    private val sums = ForceSums(fx, fy, ox, oy)
    private var collisionPadding = COLLISION_PADDING
    private var collisionStrength = COLLISION_STRENGTH

    /**
     * Applies [tuning]'s forces: [nodeKinds] holds each node's [MemoryGraphNodeKind] ordinal (its
     * charge) and [springKinds] each spring's [MemoryGraphEdgeKind] ordinal (its rest length and
     * stiffness), in the orders the field was built with (see [springKindsOf]).
     */
    fun applyTuning(tuning: MemoryGraphTuning, nodeKinds: IntArray, springKinds: IntArray) {
        val crowded = tuning.crowdednessOf(size, springs.size / 2)
        repulsion = tuning.repulsion * lerp(1f, tuning.crowdedRepulsionScale, crowded)
        cutoffSq = tuning.repulsionCutoff * tuning.repulsionCutoff
        val nodeKindValues = MemoryGraphNodeKind.entries
        for (i in 0 until min(size, nodeKinds.size)) charge[i] = tuning.chargeOf(nodeKindValues[nodeKinds[i]])
        applySpringTuning(tuning, nodeKinds, springKinds)
        applyClusterTuning(tuning, nodeKinds)
        gravity = tuning.gravity
        collisionPadding = tuning.collisionPadding
        collisionStrength = min(lerp(tuning.collisionStrength, tuning.crowdedCollisionStrength, crowded), tuning.collisionMaxShare)
        collisionFadeAlpha = tuning.collisionFadeAlpha
        quadtree = if (size > tuning.barnesHutAbove) (quadtree ?: BarnesHutTree(size)) else null
        quadtree?.rebuildEvery = tuning.barnesHutRebuildFrames.toInt().coerceAtLeast(1)
        quadtreeTerms = RepulsionTerms(repulsion, cutoffSq, CollisionTerms(collisionPadding, collisionStrength), tuning.barnesHutTheta)
    }

    /**
     * Each spring's rest length and stiffness for its kind, the stiffness scaled by
     * 1 / min(degree of its ends) and its pull split by degree (d3-force's forceLink), blended in by
     * [MemoryGraphTuning.degreeScaling]. Degrees are counted here, once per graph.
     */
    private fun applySpringTuning(tuning: MemoryGraphTuning, nodeKinds: IntArray, springKinds: IntArray) {
        // In a graph with several agents (the fleet view) every spoke from the backend is weak and
        // unsplit, and the backend may be pinned at the centre.
        val backend = nodeKinds.indexOfFirst { it == MemoryGraphNodeKind.Backend.ordinal }
        val fleet = backend >= 0 && nodeKinds.count { it == MemoryGraphNodeKind.Agent.ordinal } >= 2
        anchor = if (fleet && tuning.pinBackend) backend else FREE
        val degree = IntArray(size)
        springs.forEach { degree[it]++ }
        val tune = springTuner(tuning, degree, if (fleet) backend else FREE)
        val edgeKindValues = MemoryGraphEdgeKind.entries
        for (spring in 0 until min(restLength.size, springKinds.size)) {
            tune(spring, edgeKindValues[springKinds[spring]])
        }
    }

    private fun springTuner(tuning: MemoryGraphTuning, degree: IntArray, backend: Int): (Int, MemoryGraphEdgeKind) -> Unit {
        val blend = tuning.degreeScaling.coerceIn(0f, 1f)
        return { spring, kind ->
            val ends = SpringEnds.at(springs, spring)
            val a = degree[ends.source].coerceAtLeast(1)
            val b = degree[ends.target].coerceAtLeast(1)
            val bias = a.toFloat() / (a + b)
            val spoke = backend != FREE && ends.contains(backend)
            restLength[spring] = tuning.lengthOf(kind)
            stiffness[spring] = if (spoke) tuning.hubSpokeStrength else tuning.stiffnessOf(kind) * lerp(1f, 1f / min(a, b), blend)
            sourceWeight[spring] = if (spoke) 1f else lerp(1f, 2f * (1f - bias), blend)
            targetWeight[spring] = if (spoke) 1f else lerp(1f, 2f * bias, blend)
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
        var s = 0
        while (s < springs.size) {
            val ends = SpringEnds.at(springs, s / 2)
            addOwner(owned, nodeKinds, ends)
            addOwner(owned, nodeKinds, SpringEnds(ends.target, ends.source))
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
        return integrate(ForceStep(pinned, FREE, staticMotion))
    }

    /**
     * One live step under [motion]: the forces scaled by the cooling [alpha] (1 when hot, decaying
     * toward [MemoryGraphTuning.alphaMin]), [MemoryGraphMotion.damping] friction and a speed cap.
     * At [MemoryGraphMotion.forGraph]'s small-graph tuning and alpha 1 it is exactly [step].
     */
    fun step(pinned: Int, motion: MemoryGraphMotion, alpha: Float): Float {
        applyForces()
        applyCluster()
        return integrate(ForceStep(pinned, anchor, motion, alpha))
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

    /** Stops every node where it is, so a later heat starts from rest instead of old momentum. */
    fun rest() {
        vx.fill(0f)
        vy.fill(0f)
    }

    private fun applyForces() {
        fx.fill(0f)
        fy.fill(0f)
        ox.fill(0f)
        oy.fill(0f)
        val tree = quadtree
        val terms = quadtreeTerms
        if (tree != null && terms != null) tree.repel(bodies, terms, sums) else applyRepulsion()
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
                val force = if (repels) repulsion * (charge[i] * charge[j]) / distanceSq else 0f
                val px = dx / distance * force
                val py = dy / distance * force
                fx[i] += px
                fy[i] += py
                fx[j] -= px
                fy[j] -= py
                ox[i] += dx / distance * overlap
                oy[i] += dy / distance * overlap
                ox[j] -= dx / distance * overlap
                oy[j] -= dy / distance * overlap
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

    /** One damped step under the chosen pin, cooling and velocity policy. */
    private fun integrate(step: ForceStep): Float {
        var fastest = 0f
        // Collision is not cooled with the other forces until alpha drops under the fade threshold,
        // so overlaps keep resolving while the rest settles; an untuned field cools it like the rest.
        val collision = if (collisionFadeAlpha > 0f) min(1f, step.alpha / collisionFadeAlpha) else step.alpha
        for (i in 0 until size) {
            if (step.holds(i)) continue
            vx[i] = (vx[i] + fx[i] * step.alpha - x[i] * gravity * step.alpha + ox[i] * collision) * step.motion.damping
            vy[i] = (vy[i] + fy[i] * step.alpha - y[i] * gravity * step.alpha + oy[i] * collision) * step.motion.damping
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val scale = if (speed > step.motion.maxSpeed) step.motion.maxSpeed / speed else 1f
            if (step.motion.clampVelocity) {
                vx[i] *= scale
                vy[i] *= scale
            }
            val moved = if (step.motion.clampVelocity) 1f else scale
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

internal fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private fun addOwner(owned: Array<MutableList<Int>>, kinds: IntArray, ends: SpringEnds) {
    val kind = kinds.getOrNull(ends.source) ?: return
    if (kind == MemoryGraphNodeKind.Agent.ordinal || kind == MemoryGraphNodeKind.Backend.ordinal) return
    if (kinds.getOrNull(ends.target) == MemoryGraphNodeKind.Agent.ordinal) owned[ends.source] += ends.target
}
