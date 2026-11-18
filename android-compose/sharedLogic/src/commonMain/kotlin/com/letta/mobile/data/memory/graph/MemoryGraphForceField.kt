package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.graph.barneshut.BarnesHutTree
import com.letta.mobile.data.memory.graph.barneshut.CollisionTerms
import com.letta.mobile.data.memory.graph.barneshut.ForceBodies
import com.letta.mobile.data.memory.graph.barneshut.ForceSums
import com.letta.mobile.data.memory.graph.barneshut.RepulsionTerms
import com.letta.mobile.data.memory.graph.forces.ForceElements
import com.letta.mobile.data.memory.graph.forces.GraphElements
import com.letta.mobile.data.memory.graph.forces.Variation
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
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
    // controller's layout) steps exactly as it always has. Per-element constants (charges, springs,
    // cluster owners, the pinned node) live in [elements], precomputed per tuning.
    private var repulsion = config.repulsion
    private var cutoffSq = 0f
    private val elements = ForceElements(size, springs, config.idealEdgeLength, config.attraction)
    private var clusterOn = false
    private var gravity = config.gravity

    // The Barnes–Hut tree a big graph's repulsion walks instead of every pair (null: every pair).
    private var quadtree: BarnesHutTree? = null
    private var quadtreeTerms: RepulsionTerms? = null
    private val bodies = ForceBodies(x, y, elements.charge, radii)
    private val sums = ForceSums(fx, fy, ox, oy)
    private var collisionPadding = COLLISION_PADDING
    private var collisionStrength = COLLISION_STRENGTH

    /**
     * Applies [tuning]'s forces to [graph]'s elements (see [elementsOf]): the field-wide ones here,
     * the per-element ones (each varied by its own seed) into [elements].
     */
    fun applyTuning(tuning: MemoryGraphTuning, graph: GraphElements) {
        val crowded = tuning.crowdednessOf(size, springs.size / 2)
        repulsion = tuning.repulsion * lerp(1f, tuning.crowdedRepulsionScale, crowded)
        cutoffSq = tuning.repulsionCutoff * tuning.repulsionCutoff
        elements.apply(tuning, graph)
        clusterOn = elements.clusterStrength != 0f
        gravity = tuning.gravity
        collisionPadding = tuning.collisionPadding
        collisionStrength = min(lerp(tuning.collisionStrength, tuning.crowdedCollisionStrength, crowded), tuning.collisionMaxShare)
        collisionFadeAlpha = tuning.collisionFadeAlpha
        quadtree = if (size > tuning.barnesHutAbove) (quadtree ?: BarnesHutTree(size)) else null
        quadtree?.rebuildEvery = tuning.barnesHutRebuildFrames.toInt().coerceAtLeast(1)
        quadtreeTerms = RepulsionTerms(repulsion, cutoffSq, CollisionTerms(collisionPadding, collisionStrength), tuning.barnesHutTheta)
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
     * Under a motion with [MemoryGraphLayoutConfig]'s damping and step cap, no cooling and alpha 1,
     * it is exactly [step].
     */
    fun step(pinned: Int, motion: MemoryGraphMotion, alpha: Float): Float {
        applyForces()
        applyCluster()
        return integrate(ForceStep(pinned, elements.anchor, motion, alpha))
    }

    /** Pulls each owned node toward its owners' centroid ([MemoryGraphTuning.clusterStrength]). */
    private fun applyCluster() {
        if (!clusterOn) return
        val ownerStart = elements.ownerStart
        val owners = elements.owners
        val strength = elements.clusterStrength
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
            val pull = strength * elements.cluster[i]
            fx[i] += (cx / count - x[i]) * pull
            fy[i] += (cy / count - y[i]) * pull
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
                val force = if (repels) repulsion * (elements.charge[i] * elements.charge[j]) / distanceSq else 0f
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
            val force = elements.stiffness[spring] * (distance - elements.restLength[spring])
            val px = dx / distance * force
            val py = dy / distance * force
            fx[a] += px * elements.sourceWeight[spring]
            fy[a] += py * elements.sourceWeight[spring]
            fx[b] -= px * elements.targetWeight[spring]
            fy[b] -= py * elements.targetWeight[spring]
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
            val pull = gravity * elements.gravity[i]
            vx[i] = (vx[i] + fx[i] * step.alpha - x[i] * pull * step.alpha + ox[i] * collision) * step.motion.damping
            vy[i] = (vy[i] + fy[i] * step.alpha - y[i] * pull * step.alpha + oy[i] * collision) * step.motion.damping
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

        /**
         * [view]'s elements for [applyTuning]: each node's kind and seed (a hash of its id) and each
         * of [springsOf]'s springs' kind and seed (a hash of its two ends' ids, in either order), so
         * an element's variation never depends on what else is in the graph.
         */
        fun elementsOf(view: MemoryGraphView): GraphElements {
            val ids = view.nodes.mapTo(HashSet()) { it.id }
            val springEdges = view.edges.filter { it.fromId != it.toId && it.fromId in ids && it.toId in ids }
            return GraphElements(
                nodeKinds = IntArray(view.nodes.size) { view.nodes[it].kind.ordinal },
                springKinds = springKindsOf(view),
                nodeSeeds = IntArray(view.nodes.size) { Variation.hash(view.nodes[it].id) },
                springSeeds = springEdges.map { Variation.hashPair(it.fromId, it.toId) }.toIntArray(),
            )
        }

        private const val MIN_DISTANCE = 0.01f
        private const val MIN_DISTANCE_SQ = 1f
        private const val COINCIDENT_NUDGE = 0.5f
        private const val COLLISION_PADDING = 10f
        private const val COLLISION_STRENGTH = 0.6f
    }
}

internal fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
