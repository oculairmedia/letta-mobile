package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.LcpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/** How one plugin call ended. */
internal sealed interface CallOutcome<out T> {
    data class Answered<T>(val value: T) : CallOutcome<T>

    data class Threw(val error: Throwable) : CallOutcome<Nothing>

    data object Late : CallOutcome<Nothing>
}

/**
 * Calls the plugin as the host does: off the caller's thread (so a call that blocks a thread is
 * still timed), under the method's deadline, recording a [ConformanceRule.DEADLINE] finding for a
 * call that outlives it. The late call is cancelled and left behind, as the host leaves it.
 */
internal class DeadlineCaller(
    private val options: ConformanceOptions,
    private val finding: (ConformanceFinding) -> Unit,
) {
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun <T> call(method: LcpMethod, block: suspend () -> T): CallOutcome<T> {
        val deadline = options.deadline(method)
        val running = calls.async { runCatching { block() } }
        val result = withTimeoutOrNull(deadline) { running.await() }
        if (result == null) {
            running.cancel()
            finding(ConformanceFinding(ConformanceRule.DEADLINE, "${method.spiMember} did not answer within $deadline ms (${method.wire})"))
            return CallOutcome.Late
        }
        return result.fold({ CallOutcome.Answered(it) }, { CallOutcome.Threw(it) })
    }

    fun close() {
        calls.cancel()
    }
}
