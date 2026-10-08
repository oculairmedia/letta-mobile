package com.letta.mobile.data.controller.reconnect

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The machine went to sleep or woke up (letta-mobile-bzvro.4, F04). */
sealed interface SuspendEvent {
    data object Suspended : SuspendEvent

    /** [sleptForMs] is the measured gap, or null when the platform did not say. */
    data class Resumed(val sleptForMs: Long?) : SuspendEvent
}

/**
 * A platform source of suspend/resume events. Consumers react to [SuspendEvent.Resumed] by
 * resetting backoff and crash counters and reconnecting at once, instead of waiting out a
 * backoff that was scheduled before the sleep.
 */
fun interface SystemSuspendSignal {
    fun events(): Flow<SuspendEvent>
}

/**
 * Portable suspend detector: a ticker that compares wall-clock time across a short delay.
 *
 * While the machine sleeps no coroutine runs, so the first tick after waking sees the wall clock
 * jump by about the length of the sleep. A gap larger than [tickInterval] + [threshold] reads as
 * a sleep and emits [SuspendEvent.Suspended] followed by [SuspendEvent.Resumed]. Both arrive
 * after the wake: this detector cannot see the sleep begin, so consumers that need to stop
 * counting crashes during the sleep rely on the resume resetting the counters.
 *
 * Used as the desktop fallback where no OS power notification is wired, and anywhere else a
 * platform hook is missing.
 */
class WallClockJumpSuspendSignal(
    private val clock: Clock = Clock.System,
    private val tickInterval: Duration = DEFAULT_TICK,
    private val threshold: Duration = DEFAULT_THRESHOLD,
) : SystemSuspendSignal {
    override fun events(): Flow<SuspendEvent> = flow {
        var last = clock.now()
        while (currentCoroutineContext().isActive) {
            delay(tickInterval)
            val now = clock.now()
            val gap = now - last
            last = now
            if (gap > tickInterval + threshold) {
                emit(SuspendEvent.Suspended)
                emit(SuspendEvent.Resumed((gap - tickInterval).inWholeMilliseconds))
            }
        }
    }

    companion object {
        val DEFAULT_TICK: Duration = 5.seconds
        val DEFAULT_THRESHOLD: Duration = 20.seconds
    }
}
