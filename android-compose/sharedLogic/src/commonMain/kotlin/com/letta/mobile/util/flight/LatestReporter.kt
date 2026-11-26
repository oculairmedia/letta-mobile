package com.letta.mobile.util.flight

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports a fast-changing value at most once per [intervalMs], always ending on the latest: the first [offer] after a quiet
 * spell opens a window, and at its end [report] gets the value offered last, unless it is the one reported before. A value
 * that changes on every streamed frame so costs one telemetry line per window, and the last state before the changes stopped
 * is always on record. [offer] is a field write and, once per window, a coroutine launch in [scope]; it never waits.
 */
internal class LatestReporter<T : Any>(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val report: (T) -> Unit,
) {
    private val latest = atomic<T?>(null)
    private val scheduled = atomic(false)
    private var reported: T? = null

    fun offer(value: T) {
        latest.value = value
        if (!scheduled.compareAndSet(expect = false, update = true)) return
        scope.launch {
            delay(intervalMs)
            scheduled.value = false
            val current = latest.value ?: return@launch
            if (current == reported) return@launch
            reported = current
            report(current)
        }
    }
}
