package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable

/** A position in layout space. Layout units are density-independent (dp-like). */
@Immutable
data class MemoryGraphPoint(val x: Float, val y: Float)

@Immutable
data class MemoryGraphBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val center: MemoryGraphPoint get() = MemoryGraphPoint((minX + maxX) / 2f, (minY + maxY) / 2f)

    companion object {
        val Empty = MemoryGraphBounds(0f, 0f, 0f, 0f)

        fun of(points: Collection<MemoryGraphPoint>): MemoryGraphBounds {
            if (points.isEmpty()) return Empty
            return MemoryGraphBounds(
                minX = points.minOf { it.x },
                minY = points.minOf { it.y },
                maxX = points.maxOf { it.x },
                maxY = points.maxOf { it.y },
            )
        }
    }
}

@Immutable
data class MemoryGraphLayout(
    val positions: Map<String, MemoryGraphPoint> = emptyMap(),
    val bounds: MemoryGraphBounds = MemoryGraphBounds.Empty,
) {
    val isEmpty: Boolean get() = positions.isEmpty()

    operator fun get(nodeId: String): MemoryGraphPoint? = positions[nodeId]
}

enum class MemoryGraphLayoutMode {
    /** Organic force-directed layout (desktop default): hubs settle in the middle. */
    Force,

    /** The radial tree the force pass is seeded from, without relaxation. */
    Radial,
}

/**
 * Tuning mirrors the desktop Kuiver `ForceDirected` config (softer repulsion,
 * higher attraction so clusters stay compact). [workBudget] caps the O(n²)
 * pass so a large graph stays interactive: iterations shrink as n grows.
 */
@Immutable
data class MemoryGraphLayoutConfig(
    val iterations: Int = 420,
    val repulsion: Float = 3200f,
    val attraction: Float = 0.14f,
    val idealEdgeLength: Float = 110f,
    val damping: Float = 0.82f,
    val gravity: Float = 0.01f,
    val maxStep: Float = 30f,
    val ringSpacing: Float = 150f,
    val minArcLength: Float = 56f,
    val workBudget: Int = 3_000_000,
    val minIterations: Int = 30,
)

/**
 * Deterministic, platform-neutral graph layout. Same view in, same layout
 * out: no randomness, no clocks, no JVM-only APIs, so it runs on Android,
 * desktop and wasm alike.
 */
object MemoryGraphLayoutEngine {
    fun layout(
        view: MemoryGraphView,
        mode: MemoryGraphLayoutMode = MemoryGraphLayoutMode.Force,
        config: MemoryGraphLayoutConfig = MemoryGraphLayoutConfig(),
    ): MemoryGraphLayout {
        if (view.isEmpty) return MemoryGraphLayout()
        val seed = MemoryGraphRadialSeed.place(view, config)
        val relaxed = when (mode) {
            MemoryGraphLayoutMode.Radial -> seed
            MemoryGraphLayoutMode.Force -> MemoryGraphForceSimulation(view, config).relax(seed)
        }
        return MemoryGraphLayout(positions = relaxed, bounds = MemoryGraphBounds.of(relaxed.values))
    }
}
