package com.letta.mobile.data.memory.graph.forces

import kotlin.math.ln
import kotlin.math.min

/**
 * The per-element constants a force field steps with, precomputed whenever a tuning or graph
 * changes and read every frame without allocating or hashing: each node's charge and its gravity
 * and cluster multipliers, each spring's rest length, stiffness and how its pull splits between its
 * ends, the cluster force's owners, and the node the step never moves. Built untuned (every
 * multiplier 1, springs at [restDefault] and [stiffnessDefault]) for the controller's layout.
 */
class ForceElements(private val size: Int, private val springs: IntArray, restDefault: Float, stiffnessDefault: Float) {
    val charge = FloatArray(size) { 1f }
    val gravity = FloatArray(size) { 1f }
    val cluster = FloatArray(size) { 1f }
    val restLength = FloatArray(springs.size / 2) { restDefault }
    val stiffness = FloatArray(springs.size / 2) { stiffnessDefault }
    val sourceWeight = FloatArray(springs.size / 2) { 1f }
    val targetWeight = FloatArray(springs.size / 2) { 1f }

    /** Each node's owning agents, as a compressed list: [ownerStart] indexes [owners]. */
    var ownerStart = IntArray(size + 1)
        private set
    var owners = IntArray(0)
        private set

    /** The cluster pull for this graph: 0 unless it has two or more agents. */
    var clusterStrength = 0f
        private set

    /** The node the step never moves (the fleet's pinned backend), or -1. */
    var anchor = NONE
        private set

    fun apply(tuning: ForceElementTuning, elements: GraphElements) {
        val backend = elements.nodeKinds.indexOfFirst(tuning::isBackendKind)
        val agents = elements.nodeKinds.count(tuning::isAgentKind)
        val fleet = backend >= 0 && agents >= 2
        anchor = if (fleet && tuning.pinsBackend) backend else NONE
        clusterStrength = if (agents >= 2) tuning.clusterPull else 0f
        applyNodes(tuning, elements)
        applySprings(tuning, elements, if (fleet) backend else NONE)
        applyOwners(tuning, elements)
    }

    private fun applyNodes(tuning: ForceElementTuning, elements: GraphElements) {
        val spreads = tuning.spreads
        for (i in 0 until min(size, elements.nodeKinds.size)) {
            val seed = elements.nodeSeeds.getOrNull(i)
            charge[i] = tuning.chargeOfKind(elements.nodeKinds[i]) * vary(seed, Variation.CHARGE, spreads.charge)
            gravity[i] = vary(seed, Variation.GRAVITY, spreads.gravity)
            cluster[i] = vary(seed, Variation.CLUSTER, spreads.cluster)
        }
    }

    /**
     * Each spring's rest length (its kind's, varied by its seed and lengthened toward hubs) and
     * stiffness (its kind's, scaled by 1 / min(degree of its ends) under d3's degree scaling, then
     * varied), its pull split toward its lower-degree end; a fleet's backend spokes are weak and
     * unsplit. Degrees are counted here, once per graph.
     */
    private fun applySprings(tuning: ForceElementTuning, elements: GraphElements, backend: Int) {
        val degree = IntArray(size)
        springs.forEach { degree[it]++ }
        val blend = tuning.degreeBlend.coerceIn(0f, 1f)
        val stiffnessSpread = tuning.spreads.stiffness
        for (spring in 0 until min(restLength.size, elements.springKinds.size)) {
            val ends = SpringEnds.at(springs, spring)
            val kind = elements.springKinds[spring]
            val seed = elements.springSeeds.getOrNull(spring)
            val a = degree[ends.source].coerceAtLeast(1)
            val b = degree[ends.target].coerceAtLeast(1)
            val bias = a.toFloat() / (a + b)
            val spoke = backend != NONE && ends.contains(backend)
            val length = vary(seed, Variation.LENGTH, tuning.lengthSpreadOfKind(kind)) * hubLength(tuning, maxOf(a, b))
            restLength[spring] = tuning.lengthOfKind(kind) * length
            val base = if (spoke) tuning.spokeStiffness else tuning.stiffnessOfKind(kind) * lerp(1f, 1f / min(a, b), blend)
            stiffness[spring] = base * vary(seed, Variation.STIFFNESS, stiffnessSpread)
            sourceWeight[spring] = if (spoke) 1f else lerp(1f, 2f * (1f - bias), blend)
            targetWeight[spring] = if (spoke) 1f else lerp(1f, 2f * bias, blend)
        }
    }

    /** The cluster force's owners: each node linked to agents (a label group or block) is owned by them. */
    private fun applyOwners(tuning: ForceElementTuning, elements: GraphElements) {
        val kinds = elements.nodeKinds
        val owned = Array(size) { mutableListOf<Int>() }
        for (spring in 0 until springs.size / 2) {
            val ends = SpringEnds.at(springs, spring)
            if (owns(tuning, kinds, ends.target, ends.source)) owned[ends.source] += ends.target
            if (owns(tuning, kinds, ends.source, ends.target)) owned[ends.target] += ends.source
        }
        ownerStart = IntArray(size + 1)
        for (i in 0 until size) ownerStart[i + 1] = ownerStart[i] + owned[i].size
        owners = owned.flatMap { it }.toIntArray()
    }

    /** Whether [owner] owns [node]: an agent owns what links to it, except other agents and the backend. */
    private fun owns(tuning: ForceElementTuning, kinds: IntArray, owner: Int, node: Int): Boolean {
        val nodeKind = kinds.getOrNull(node) ?: return false
        val ownerKind = kinds.getOrNull(owner) ?: return false
        return tuning.isAgentKind(ownerKind) && !tuning.isAgentKind(nodeKind) && !tuning.isBackendKind(nodeKind)
    }

    private companion object {
        const val NONE = -1

        /** A node with this many links counts as a full hub for the hub length bias. */
        const val HUB_DEGREE = 64f

        fun vary(seed: Int?, salt: Int, spread: Float): Float = if (seed == null) 1f else Variation.factor(seed, salt, spread)

        /** 1 plus the hub length bias times how much of a hub the spring's busier end is. */
        fun hubLength(tuning: ForceElementTuning, degree: Int): Float {
            if (tuning.hubLengthBias == 0f || degree <= 1) return 1f
            return 1f + tuning.hubLengthBias * min(1f, ln(degree.toFloat()) / ln(HUB_DEGREE))
        }

        fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
    }
}
