package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import kotlin.math.pow

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
            val t = tuning.crowdednessOf(nodes, edges)
            if (t == 0f) return MemoryGraphMotion(tuning.sparseDamping, tuning.sparseMaxSpeed, alphaDecay = 0f, clampVelocity = false)
            val coolingSteps = tuning.coolingFrames.coerceAtLeast(1f)
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
