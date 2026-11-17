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
 * a spring per edge kind, gravity toward the centre, collision between drawn circles) and the
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
    /** Each edge kind's rest length. */
    val springLength: Map<MemoryGraphEdgeKind, Float> = MemoryGraphEdgeKind.entries.associateWith { 110f },
    /** Each edge kind's stiffness: force per unit stretched past its rest length. */
    val springStiffness: Map<MemoryGraphEdgeKind, Float> = MemoryGraphEdgeKind.entries.associateWith { 0.14f },
    /** The pull of every node toward the centre, per unit of distance. */
    val gravity: Float = 0.01f,
    /** How far apart two drawn circles are pushed beyond touching. */
    val collisionPadding: Float = 10f,
    /** How hard overlapping circles push apart, per unit of overlap. */
    val collisionStrength: Float = 0.6f,
    /** Each node kind's collision radius, as a multiple of its drawn radius. */
    val collisionRadiusScale: Map<MemoryGraphNodeKind, Float> = MemoryGraphNodeKind.entries.associateWith { 1f },
    /** Velocity kept per frame on a sparse graph (one agent's star). */
    val sparseDamping: Float = 0.82f,
    /** Velocity kept per frame on a crowded graph (the fleet view). */
    val crowdedDamping: Float = 0.6f,
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
    /** Frames a fully crowded graph takes to cool from [alphaStart] to [alphaMin]. */
    val coolingFrames: Float = 150f,
    /** Below this alpha a cooling simulation stops. */
    val alphaMin: Float = 0.001f,
    /** Once every node moves slower than this per frame, the simulation stops. */
    val settleSpeed: Float = 0.04f,
    /** The most frames one heat runs for, cooled or not. */
    val maxFramesPerHeat: Float = 360f,
) {
    /** [kind]'s charge (1 when unset). */
    fun chargeOf(kind: MemoryGraphNodeKind): Float = nodeCharge[kind] ?: 1f

    /** [kind]'s spring rest length (the default when unset). */
    fun lengthOf(kind: MemoryGraphEdgeKind): Float = springLength[kind] ?: DEFAULT_SPRING_LENGTH

    /** [kind]'s spring stiffness (the default when unset). */
    fun stiffnessOf(kind: MemoryGraphEdgeKind): Float = springStiffness[kind] ?: DEFAULT_SPRING_STIFFNESS

    /** [kind]'s collision radius multiple (1 when unset). */
    fun radiusScaleOf(kind: MemoryGraphNodeKind): Float = collisionRadiusScale[kind] ?: 1f
}

private const val DEFAULT_SPRING_LENGTH = 110f
private const val DEFAULT_SPRING_STIFFNESS = 0.14f

/**
 * The one [MemoryGraphTuning] the live memory graph reads. Production never changes it, so it is
 * always the defaults; a dev tuning window sets it, and every open graph re-applies it and heats.
 */
object MemoryGraphTunings {
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
            "springLength" to tuning.springLength.literal("MemoryGraphEdgeKind"),
            "springStiffness" to tuning.springStiffness.literal("MemoryGraphEdgeKind"),
            "gravity" to tuning.gravity.literal(),
            "collisionPadding" to tuning.collisionPadding.literal(),
            "collisionStrength" to tuning.collisionStrength.literal(),
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
        )
        return fields.joinToString(separator = ",\n", prefix = "MemoryGraphTuning(\n", postfix = ",\n)") { (name, value) ->
            "    $name = $value"
        }
    }

    private fun Float.literal(): String = "${this}f"

    private fun <K : Enum<K>> Map<K, Float>.literal(type: String): String =
        entries.joinToString(prefix = "mapOf(", postfix = ")") { (kind, value) -> "$type.${kind.name} to ${value.literal()}" }
}
