package com.letta.mobile.avatar.core

import java.lang.management.ManagementFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.1 budget: one director tick (state + gaze + reactions + listening) stays
 * under 0.2 ms per entry on the JVM, and the director layer adds no per-tick allocation over the
 * AvatarDirector + GazeDirector pair each entry already ticked before it existed.
 *
 * The gaze pose itself is still a fresh value per tick (GazePose / AvatarLookTarget); making that
 * allocation-free is the perf slice's (letta-mobile-te3n1.6), so it is measured as the baseline
 * here rather than asserted away.
 */
class MascotDirectorBudgetTest {
    /** A renderer that does nothing, so only the director's own work is measured. */
    private class SilentRuntime : AvatarRuntime, AvatarHeadTurn {
        override val state: StateFlow<AvatarRuntimeState> = MutableStateFlow(AvatarRuntimeState.Idle)
        override suspend fun load(model: AvatarModel) = Unit
        override suspend fun unload() = Unit
        override fun setExpression(expression: AvatarExpression, weight: Float) = Unit
        override fun setMouthOpen(value: Float) = Unit
        override fun setLookTarget(target: AvatarLookTarget?) = Unit
        override fun playGesture(gesture: AvatarGesture, fadeSeconds: Float) = Unit
        override fun playAnimation(animationId: String, loop: Boolean) = Unit
        override fun setAccessoryEnabled(accessoryId: String, enabled: Boolean) = Unit
        override fun update(deltaSeconds: Float) = Unit
        override fun dispose() = Unit
        override fun setHeadTurn(turnX: Float, turnY: Float) = Unit
    }

    private val world = GazeWorld.fromWindow(
        GazeWindow(
            mascot = GazeRect(100f, 100f, 164f, 164f),
            reach = GazeReach(360f),
            pointerPx = GazePoint(400f, 300f),
            rects = GazeTargetRects(
                input = GazeRect(0f, 600f, 800f, 660f),
                timeline = GazeRect(0f, 0f, 800f, 580f),
                // te3n1.2: every attention kind in play, so the per-tick attention work is measured too.
                attention = AttentionKind.entries.map { kind ->
                    AttentionRect(AttentionKey(kind.name), kind, GazeRect(200f, 300f + kind.ordinal * 40f, 600f, 330f + kind.ordinal * 40f))
                },
            ),
        ),
    )

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBytes(): Long = threads.currentThreadAllocatedBytes

    private fun mascot(): MascotDirector = MascotDirector(SilentRuntime(), Random(3)).also { director ->
        director.setGazeWorld(world)
        director.setSignals(MascotSignals(activity = AvatarActivity.THINKING, userTyping = true))
    }

    @Test
    fun aTickFitsTheFrameBudget() {
        val director = mascot()
        repeat(WARMUP) { director.setKeystrokes(it / 6); director.tick(STEP) }
        val start = System.nanoTime()
        repeat(TICKS) { director.setKeystrokes(it / 6); director.tick(STEP) }
        val perTickMs = (System.nanoTime() - start) / 1e6 / TICKS
        println("MascotDirector.tick: %.4f ms/tick".format(perTickMs))
        assertTrue(perTickMs <= BUDGET_MS, "tick took $perTickMs ms, budget $BUDGET_MS ms")
    }

    @Test
    fun theDirectorLayerAllocatesNothingPerTick() {
        // Baseline: what an entry ticked before the director existed (avatar + gaze + its writes).
        val runtime = SilentRuntime()
        val random = Random(3)
        val avatar = AvatarDirector(runtime, random).also { it.setActivity(AvatarActivity.THINKING) }
        val gaze = GazeDirector(random)
        val baseline = {
            avatar.tick(STEP)
            val pose = gaze.tick(STEP, avatar.state, world)
            avatar.setLookTarget(pose.lookTarget)
            runtime.setHeadTurn(pose.headX, pose.headY)
        }
        repeat(WARMUP) { baseline() }
        val baselineStart = allocatedBytes()
        repeat(TICKS) { baseline() }
        val baselinePerTick = (allocatedBytes() - baselineStart).toDouble() / TICKS

        val director = mascot()
        repeat(WARMUP) { director.setKeystrokes(it / 6); director.tick(STEP) }
        val start = allocatedBytes()
        repeat(TICKS) { director.setKeystrokes(it / 6); director.tick(STEP) }
        val perTick = (allocatedBytes() - start).toDouble() / TICKS

        println("allocation: director %.1f B/tick, avatar+gaze baseline %.1f B/tick".format(perTick, baselinePerTick))
        assertTrue(perTick <= baselinePerTick + NOISE_BYTES, "the director layer allocates ${perTick - baselinePerTick} B/tick of its own")
    }

    @Test
    fun attentionPointsAddNoAllocationToAGazeTick() {
        // te3n1.2: the attention lookups, interest clock and containment clamp run every tick on
        // primitives; a gaze tick with every kind in play allocates no more than one with none.
        val bare = world.copy(attention = emptyList())
        fun perTick(of: GazeWorld): Double {
            val gaze = GazeDirector(Random(3))
            repeat(WARMUP) { gaze.tick(STEP, AvatarState.WORKING, of) }
            val start = allocatedBytes()
            repeat(TICKS) { gaze.tick(STEP, AvatarState.WORKING, of) }
            return (allocatedBytes() - start).toDouble() / TICKS
        }
        val without = perTick(bare)
        val with = perTick(world)
        println("gaze allocation: %.1f B/tick with attention, %.1f B/tick without".format(with, without))
        assertTrue(with <= without + NOISE_BYTES, "attention adds ${with - without} B/tick")
    }

    private companion object {
        const val STEP = 1f / 60f
        const val WARMUP = 20_000
        const val TICKS = 20_000
        const val BUDGET_MS = 0.2
        /** Measurement noise of the allocation counter over this many ticks, per tick. */
        const val NOISE_BYTES = 4.0
    }
}
