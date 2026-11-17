package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Every knob of the memory page's live graph simulation, with the shipped values as defaults: the
 * forces [MemoryGraphForceField] applies (many-body repulsion scaled by each node kind's charge,
 * a degree-scaled spring per edge kind, a cluster pull toward each node's agents, gravity toward
 * the centre, collision between drawn circles), how a graph with several agents is seeded, which
 * nodes the renderer labels, and the
 * motion [MemoryGraphMotion] steps them with (friction, speed caps and cooling, tuned by how
 * crowded the graph is). The controller's deterministic layout keeps [MemoryGraphLayoutConfig];
 * this tunes only the live view.
 */
@Serializable
@Immutable
data class MemoryGraphTuning(
    /** Many-body repulsion between every pair of nodes, falling off with the square of distance. */
    val repulsion: Float = 3200f,
    /** Each node kind's charge; a pair repels with [repulsion] times both charges. */
    val nodeCharge: Map<MemoryGraphNodeKind, Float> = MemoryGraphNodeKind.entries.associateWith { 1f },
    /** Beyond this distance two nodes stop repelling; 0 repels at any distance. */
    val repulsionCutoff: Float = 0f,
    /** Repulsion on a fully crowded graph, as a multiple of [repulsion] (blended in by crowding). */
    val crowdedRepulsionScale: Float = 3f,
    /** Above this many nodes repulsion uses a Barnes–Hut quadtree instead of every pair. */
    val barnesHutAbove: Float = 300f,
    /** Barnes–Hut's opening angle: a cell this small against its distance counts as one body. */
    val barnesHutTheta: Float = 0.9f,
    /** Frames between rebuilding the Barnes–Hut tree (in between its cells follow their bodies), so its forces do not jump each frame. */
    val barnesHutRebuildFrames: Float = 10f,
    /** Each edge kind's rest length. */
    val springLength: Map<MemoryGraphEdgeKind, Float> = MemoryGraphEdgeKind.entries.associateWith { 110f },
    /** Each edge kind's stiffness: force per unit stretched past its rest length. */
    val springStiffness: Map<MemoryGraphEdgeKind, Float> = MemoryGraphEdgeKind.entries.associateWith { 0.14f },
    /**
     * In a graph with several agents, the stiffness of every spoke from the backend: weak, so the
     * backend's hundreds of spokes do not drag the agents into a ring around it.
     */
    val hubSpokeStrength: Float = 0.02f,
    /** In a graph with several agents, whether the backend stays fixed at the centre. */
    val pinBackend: Boolean = true,
    /**
     * How far d3-force's degree scaling is blended in, 0 (off) to 1 (full): a spring's stiffness
     * divided by the smaller of its ends' degrees, and its pull split toward the lower-degree end so
     * hubs move less.
     */
    val degreeScaling: Float = 1f,
    /** Whether shared label groups are pulled toward their agents' centroid (and blocks toward their agent). */
    val clusterEnabled: Boolean = true,
    /** The cluster pull per unit of distance from the owners' centroid, scaled by the cooling alpha. */
    val clusterStrength: Float = 0.05f,
    /**
     * Whether a graph with several agents opens from a seeded layout: agents on a sunflower spiral
     * around the backend at the centre, each other node near its agents' centroid.
     */
    val seededLayout: Boolean = true,
    /** Seeded agents spiral out from the centre (a sunflower pattern), this far apart per square root of their rank. */
    val seedSpacing: Float = 60f,
    /** How far from its agents' centroid a seeded node may start (deterministic, by node id). */
    val seedJitter: Float = 30f,
    /** The pull of every node toward the centre, per unit of distance. */
    val gravity: Float = 0.01f,
    /** How far apart two drawn circles are pushed beyond touching. */
    val collisionPadding: Float = 10f,
    /** How hard overlapping circles push apart, per unit of overlap. */
    val collisionStrength: Float = 0.6f,
    /** Collision strength on a fully crowded graph (blended in by crowding). */
    val crowdedCollisionStrength: Float = 3f,
    /**
     * The most of an overlap a node is pushed per frame (with its partner, twice this): stronger
     * collision overshoots, so overlapping nodes bounce off each other instead of settling.
     */
    val collisionMaxShare: Float = 0.35f,
    /** Collision cools with the other forces only once alpha falls below this, so overlaps keep resolving. */
    val collisionFadeAlpha: Float = 0.1f,
    /** Each node kind's collision radius, as a multiple of its drawn radius. */
    val collisionRadiusScale: Map<MemoryGraphNodeKind, Float> = MemoryGraphNodeKind.entries.associateWith { 1f },
    /** Velocity kept per frame on a sparse graph (one agent's star). */
    val sparseDamping: Float = 0.75f,
    /** Velocity kept per frame on a crowded graph (the fleet view). */
    val crowdedDamping: Float = 0.45f,
    /** The fastest a node moves per frame on a sparse graph. */
    val sparseMaxSpeed: Float = 30f,
    /** The fastest a node moves per frame on a crowded graph. */
    val crowdedMaxSpeed: Float = 12f,
    /** Crowding (nodes plus twice each edge beyond a tree's) at or under which a graph is sparse. */
    val sparseCrowding: Float = 40f,
    /** Crowding at or over which a graph is fully crowded. */
    val crowdedCrowding: Float = 240f,
    /** The cooling's starting alpha, which the forces are scaled by. */
    val alphaStart: Float = 1f,
    /** Frames a crowded graph takes to cool from [alphaStart] to [alphaMin]. */
    val coolingFrames: Float = 150f,
    /** Below this alpha a cooling simulation stops. */
    val alphaMin: Float = 0.001f,
    /** Once every node moves slower than this per frame, the simulation stops. */
    val settleSpeed: Float = 0.04f,
    /** The most frames one heat runs for, cooled or not. */
    val maxFramesPerHeat: Float = 360f,
    /** A node with at least this many links keeps its label (shared hubs such as "persona"). */
    val labelMinDegree: Float = 3f,
    /** From this zoom on every node may be labelled. */
    val labelZoomThreshold: Float = 1.6f,
    /** A graph with at most this many nodes labels every node (one agent's star). */
    val labelAllUnderNodes: Float = 60f,
    /** Whether a label that would overlap a higher-priority one is skipped. */
    val labelCulling: Boolean = true,
    /** Whether hovering or selecting a node also labels its neighbours. */
    val labelNeighboursOnFocus: Boolean = true,
    /** Label text size, as a multiple of the graph's label styles. */
    val labelFontScale: Float = 1f,
    /** Decisions running a label must overlap (or be clear) before culling hides (or shows) it. */
    val labelHysteresisCount: Float = 3f,
    /** Pixels a shown label's chip is shrunk (and a hidden one's grown) by when culling tests it. */
    val labelHysteresisMargin: Float = 6f,
    /** While the graph moves, frames between culling decisions; at rest the labels hold until focus or zoom changes. */
    val labelCullInterval: Float = 8f,
) {
    /** [kind]'s charge (1 when unset). */
    fun chargeOf(kind: MemoryGraphNodeKind): Float = nodeCharge[kind] ?: 1f

    /** [kind]'s spring rest length (the default when unset). */
    fun lengthOf(kind: MemoryGraphEdgeKind): Float = springLength[kind] ?: DEFAULT_SPRING_LENGTH

    /** [kind]'s spring stiffness (the default when unset). */
    fun stiffnessOf(kind: MemoryGraphEdgeKind): Float = springStiffness[kind] ?: DEFAULT_SPRING_STIFFNESS

    /** [kind]'s collision radius multiple (1 when unset). */
    fun radiusScaleOf(kind: MemoryGraphNodeKind): Float = collisionRadiusScale[kind] ?: 1f

    /**
     * How crowded a graph of [nodes] nodes and [edges] edges is, 0 (sparse: one agent's star) to 1
     * (crowded: the fleet view). Crowding counts every node plus twice each edge beyond a tree's.
     */
    fun crowdednessOf(nodes: Int, edges: Int): Float {
        val crowding = nodes + 2 * (edges - nodes).coerceAtLeast(0)
        val span = (crowdedCrowding - sparseCrowding).coerceAtLeast(1f)
        return ((crowding - sparseCrowding) / span).coerceIn(0f, 1f)
    }
}

private const val DEFAULT_SPRING_LENGTH = 110f
private const val DEFAULT_SPRING_STIFFNESS = 0.14f

/**
 * Lifecycle-owned tuning shared by a graph host and its optional developer window.
 */
class MemoryGraphTunings {
    private val state = MutableStateFlow(MemoryGraphTuning())
    private val reheats = MutableStateFlow(0)

    /** The tuning in force. */
    val current: StateFlow<MemoryGraphTuning> = state.asStateFlow()

    /** Counts reheat requests; an open graph heats whenever it changes. */
    val reheatRequests: StateFlow<Int> = reheats.asStateFlow()

    fun set(tuning: MemoryGraphTuning) {
        state.value = tuning
    }

    fun reset() = set(MemoryGraphTuning())

    /** Sets every open graph moving again from where it is. */
    fun reheat() {
        reheats.value += 1
    }

    companion object {
        /** [tuning] as JSON, for a dev store. */
        fun encode(tuning: MemoryGraphTuning): String = LettaJson.persisted.encodeToString(MemoryGraphTuning.serializer(), tuning)

        /** A stored tuning, or null when [text] is not one; keys it lacks take their defaults. */
        fun decode(text: String): MemoryGraphTuning? =
            runCatching { LettaJson.persisted.decodeFromString(MemoryGraphTuning.serializer(), text) }.getOrNull()

        /** [tuning] as the Kotlin that constructs it, for baking a tuned set in as the defaults. */
        fun kotlinSource(tuning: MemoryGraphTuning): String {
            val fields = listOf(
                "repulsion" to tuning.repulsion.literal(),
                "nodeCharge" to tuning.nodeCharge.literal("MemoryGraphNodeKind"),
                "repulsionCutoff" to tuning.repulsionCutoff.literal(),
                "crowdedRepulsionScale" to tuning.crowdedRepulsionScale.literal(),
                "barnesHutAbove" to tuning.barnesHutAbove.literal(),
                "barnesHutTheta" to tuning.barnesHutTheta.literal(),
                "barnesHutRebuildFrames" to tuning.barnesHutRebuildFrames.literal(),
                "springLength" to tuning.springLength.literal("MemoryGraphEdgeKind"),
                "springStiffness" to tuning.springStiffness.literal("MemoryGraphEdgeKind"),
                "hubSpokeStrength" to tuning.hubSpokeStrength.literal(),
                "pinBackend" to tuning.pinBackend.toString(),
                "degreeScaling" to tuning.degreeScaling.literal(),
                "clusterEnabled" to tuning.clusterEnabled.toString(),
                "clusterStrength" to tuning.clusterStrength.literal(),
                "seededLayout" to tuning.seededLayout.toString(),
                "seedSpacing" to tuning.seedSpacing.literal(),
                "seedJitter" to tuning.seedJitter.literal(),
                "gravity" to tuning.gravity.literal(),
                "collisionPadding" to tuning.collisionPadding.literal(),
                "collisionStrength" to tuning.collisionStrength.literal(),
                "crowdedCollisionStrength" to tuning.crowdedCollisionStrength.literal(),
                "collisionMaxShare" to tuning.collisionMaxShare.literal(),
                "collisionFadeAlpha" to tuning.collisionFadeAlpha.literal(),
                "collisionRadiusScale" to tuning.collisionRadiusScale.literal("MemoryGraphNodeKind"),
                "sparseDamping" to tuning.sparseDamping.literal(),
                "crowdedDamping" to tuning.crowdedDamping.literal(),
                "sparseMaxSpeed" to tuning.sparseMaxSpeed.literal(),
                "crowdedMaxSpeed" to tuning.crowdedMaxSpeed.literal(),
                "sparseCrowding" to tuning.sparseCrowding.literal(),
                "crowdedCrowding" to tuning.crowdedCrowding.literal(),
                "alphaStart" to tuning.alphaStart.literal(),
                "coolingFrames" to tuning.coolingFrames.literal(),
                "alphaMin" to tuning.alphaMin.literal(),
                "settleSpeed" to tuning.settleSpeed.literal(),
                "maxFramesPerHeat" to tuning.maxFramesPerHeat.literal(),
                "labelMinDegree" to tuning.labelMinDegree.literal(),
                "labelZoomThreshold" to tuning.labelZoomThreshold.literal(),
                "labelAllUnderNodes" to tuning.labelAllUnderNodes.literal(),
                "labelCulling" to tuning.labelCulling.toString(),
                "labelNeighboursOnFocus" to tuning.labelNeighboursOnFocus.toString(),
                "labelFontScale" to tuning.labelFontScale.literal(),
                "labelHysteresisCount" to tuning.labelHysteresisCount.literal(),
                "labelHysteresisMargin" to tuning.labelHysteresisMargin.literal(),
                "labelCullInterval" to tuning.labelCullInterval.literal(),
            )
            return fields.joinToString(separator = ",\n", prefix = "MemoryGraphTuning(\n", postfix = ",\n)") { (name, value) ->
                "    $name = $value"
            }
        }

        private fun Float.literal(): String = "${this}f"

        private fun <K : Enum<K>> Map<K, Float>.literal(type: String): String =
            entries.joinToString(prefix = "mapOf(", postfix = ")") { (kind, value) -> "$type.${kind.name} to ${value.literal()}" }
    }
}
