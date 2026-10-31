package com.letta.mobile.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphView
import kotlin.math.sqrt
import kotlinx.coroutines.channels.Channel

/**
 * The positions the memory graph draws and hit-tests against, moved by [MemoryGraphPhysics].
 * A conflated wake channel starts a per-frame loop only while the simulation is hot (a bloom, a
 * drag, nodes added); once it settles the loop parks, so a still graph does no work per frame.
 */
@Stable
internal class MemoryGraphLiveLayout {
    var layout: MemoryGraphLayout by mutableStateOf(MemoryGraphLayout())
        private set

    private var physics: MemoryGraphPhysics? = null

    /** Nothing is moving: [layout] is where nodes will stay until the next drag or graph change. */
    val isSettled: Boolean get() = physics?.isHot != true
    private val wakes = Channel<Unit>(Channel.CONFLATED)

    /** Rebuild the simulation for a new view/target, keeping positions of nodes already shown. */
    fun retarget(view: MemoryGraphView, target: MemoryGraphLayout, looks: Map<String, MemoryNodeLook>, motion: Boolean) {
        val previous = layout.takeUnless { it.isEmpty }
        val next = MemoryGraphPhysics.create(
            view = view,
            target = target,
            radiusOf = { id -> looks[id]?.radius ?: MemoryNodeLooks.SMALL_RADIUS },
            start = MemoryGraphPhysicsStart(previous = previous, bloom = true, motion = motion),
        )
        physics = next
        layout = next.snapshot()
        wake()
    }

    /** Hold node [id] at [point] (layout space) while the rest follow. */
    fun drag(id: String, point: MemoryGraphPoint) {
        val sim = physics ?: return
        sim.pin(id, point)
        layout = sim.snapshot()
        wake()
    }

    fun endDrag() {
        physics?.release()
        wake()
    }

    /** Tick once per frame while hot; park between heats. Runs for the canvas's lifetime. */
    suspend fun run() {
        while (true) {
            wakes.receive()
            while (physics?.isHot == true) {
                withFrameNanos { }
                val sim = physics ?: break
                sim.tick()
                layout = sim.snapshot()
            }
        }
    }

    private fun wake() {
        if (physics?.isHot == true) wakes.trySend(Unit)
    }
}

/** Keeps a [MemoryGraphLiveLayout] retargeted (before the next draw) whenever the graph changes. */
@Composable
internal fun rememberMemoryGraphLiveLayout(
    view: MemoryGraphView,
    target: MemoryGraphLayout,
    looks: Map<String, MemoryNodeLook>,
    motion: Boolean,
): MemoryGraphLiveLayout {
    val live = remember { MemoryGraphLiveLayout() }
    DisposableEffect(view, target, looks, motion) {
        live.retarget(view, target, looks, motion)
        onDispose { }
    }
    LaunchedEffect(live) { live.run() }
    return live
}

/**
 * How one node reads at a glance: its [radius] (layout units ≈ dp) and, for a memory block with a
 * limit, how full it is ([usage] in 0..1).
 */
@Immutable
internal data class MemoryNodeLook(val radius: Float, val usage: Float? = null)

internal object MemoryNodeLooks {
    const val SMALL_RADIUS = 9f
    private const val ROOT_RADIUS = 22f
    private const val BLOCK_MIN_RADIUS = 8f
    private const val BLOCK_RADIUS_SPAN = 11f

    /** Blocks grow with sqrt(chars) against the biggest block, so a 4,000-char block reads big. */
    fun build(view: MemoryGraphView, memory: MemoryParityState): Map<String, MemoryNodeLook> {
        val blocks = blocksById(memory)
        val largest = blocks.values.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        return view.nodes.associate { node -> node.id to lookOf(node, blocks[node.sourceItemId], largest) }
    }

    private fun lookOf(node: MemoryGraphNode, block: MemoryParityItem.MemoryBlock?, largest: Int): MemoryNodeLook = when {
        node.kind == MemoryGraphNodeKind.Agent || node.kind == MemoryGraphNodeKind.Backend -> MemoryNodeLook(ROOT_RADIUS)
        block != null -> MemoryNodeLook(
            radius = BLOCK_MIN_RADIUS + BLOCK_RADIUS_SPAN * sqrt(block.length.toFloat() / largest),
            usage = block.limit?.takeIf { it > 0 }?.let { (block.length.toFloat() / it).coerceIn(0f, 1f) },
        )
        else -> MemoryNodeLook(SMALL_RADIUS)
    }

    private fun blocksById(memory: MemoryParityState): Map<String, MemoryParityItem.MemoryBlock> =
        memory.sections.flatMap { it.items }.filterIsInstance<MemoryParityItem.MemoryBlock>().associateBy { it.id }
}
