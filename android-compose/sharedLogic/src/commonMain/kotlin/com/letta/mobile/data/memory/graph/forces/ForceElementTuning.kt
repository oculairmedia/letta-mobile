package com.letta.mobile.data.memory.graph.forces

/** How far each per-element constant strays, as a fraction either way (already scaled by any master). */
class ElementSpreads(val charge: Float, val gravity: Float, val cluster: Float, val stiffness: Float)

/** Each per-node and per-spring constant's base value and its spreads, by kind ordinal. */
interface ForceElementTuning {
    fun chargeOfKind(kind: Int): Float
    fun lengthOfKind(kind: Int): Float
    fun stiffnessOfKind(kind: Int): Float

    /** How far a spring of this kind's rest length strays, as a fraction either way. */
    fun lengthSpreadOfKind(kind: Int): Float
    fun isAgentKind(kind: Int): Boolean
    fun isBackendKind(kind: Int): Boolean

    val spreads: ElementSpreads

    /** How far d3's degree scaling of springs is blended in, 0 to 1. */
    val degreeBlend: Float

    /** The stiffness of a backend spoke in a graph with several agents. */
    val spokeStiffness: Float

    /** Whether a graph with several agents pins its backend. */
    val pinsBackend: Boolean

    /** The cluster pull, or 0 for none. */
    val clusterPull: Float

    /** How much longer an edge to a hub is, at most. */
    val hubLengthBias: Float
}
