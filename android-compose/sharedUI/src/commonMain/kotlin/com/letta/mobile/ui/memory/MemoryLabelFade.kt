package com.letta.mobile.ui.memory

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel

/**
 * Each label's opacity, eased toward shown (1) or hidden (0) over a duration instead of popping; a
 * zero duration makes every change instant (reduced motion). Each frame [advance]s it by the time
 * since the last; while anything is still fading, [frameNanos] ticks every frame so the canvas
 * redraws, then the clock parks.
 */
@Stable
internal class MemoryLabelFade {
    private val alphas = HashMap<String, Float>()
    private var lastNanos = 0L
    private var wasFading = false
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val clock = TimeSource.Monotonic.markNow()

    /** How long a label takes to fade in or out; 0 is instant (reduced motion). */
    var durationNanos: Long = DEFAULT_DURATION_NANOS

    /** [advance] on this fade's own monotonic clock and [durationNanos]. */
    fun advance(shown: Set<String>): Boolean = advance(shown, clock.elapsedNow().inWholeNanoseconds, durationNanos)

    /** The frame clock the canvas reads while labels fade, so it redraws each frame until they settle. */
    var frameNanos by mutableLongStateOf(0L)
        private set

    /** [id]'s opacity this frame. */
    fun alphaOf(id: String): Float = alphas[id] ?: 0f

    /** Every node still partly visible: the shown ones and those fading out. */
    fun visible(): Set<String> = alphas.keys

    /**
     * Move every opacity toward [shown] by the time since the last fading frame ([nowNanos] on a
     * monotonic clock; a fade that is just starting moves one frame's worth); returns whether any is
     * still fading.
     */
    fun advance(shown: Set<String>, nowNanos: Long, durationNanos: Long): Boolean {
        val instant = durationNanos <= 0L
        val elapsed = if (wasFading) nowNanos - lastNanos else FRAME_NANOS
        val step = if (instant) 1f else elapsed.toFloat() / durationNanos
        lastNanos = nowNanos
        shown.forEach { id -> if (id !in alphas) alphas[id] = if (instant || !wasStarted) 1f else 0f }
        wasStarted = true
        val fading = advanceEntries(shown, step)
        wasFading = fading
        if (fading) wakes.trySend(Unit)
        return fading
    }

    private fun advanceEntries(shown: Set<String>, step: Float): Boolean {
        var fading = false
        val iterator = alphas.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = if (entry.key in shown) 1f else 0f
            val next = toward(entry.value, target, step)
            if (next == 0f && target == 0f) iterator.remove() else entry.setValue(next)
            if (next != target) fading = true
        }
        return fading
    }

    private fun toward(value: Float, target: Float, step: Float): Float = when {
        target > value -> (value + step).coerceAtMost(1f)
        target < value -> (value - step).coerceAtLeast(0f)
        else -> target
    }

    /** The first frame shows its labels at once; only later changes fade. */
    private var wasStarted = false

    /** Ticks [frameNanos] each frame while labels fade; parks between fades. Runs for the canvas's lifetime. */
    suspend fun run() {
        while (true) {
            wakes.receive()
            withFrameNanos { now -> frameNanos = now }
        }
    }

    companion object {
        private const val FRAME_NANOS = 16_666_667L

        /** A short fade: long enough to read as a fade, short enough not to lag a hover. */
        const val DEFAULT_DURATION_NANOS = 160_000_000L
    }
}
