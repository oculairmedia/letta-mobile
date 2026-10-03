package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.LcpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** How one plugin call ended. */
internal sealed interface CallOutcome<out T> {
    data class Answered<T>(val value: T) : CallOutcome<T>

    data class Threw(val error: Throwable) : CallOutcome<Nothing>

    data object Late : CallOutcome<Nothing>
}

/**
 * Calls the plugin as the host does: on [dispatcher], off the caller's thread, under the method's
 * deadline, recording a [ConformanceRule.DEADLINE] finding for a call that outlives it. A blocking
 * call ([callBlocking]) is interrupted at its deadline; a suspending one ([call]) is cancelled.
 */
internal class DeadlineCaller(
    private val options: ConformanceOptions,
    private val finding: (ConformanceFinding) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A suspending plugin member. */
    suspend fun <T> call(method: LcpMethod, block: suspend () -> T): CallOutcome<T> =
        timed(method) { withContext(dispatcher) { block() } }

    /** A blocking plugin member: its thread is interrupted when the deadline passes. */
    suspend fun <T> callBlocking(method: LcpMethod, block: () -> T): CallOutcome<T> =
        timed(method) { runInterruptible(dispatcher) { block() } }

    private suspend fun <T> timed(method: LcpMethod, run: suspend () -> T): CallOutcome<T> {
        val deadline = options.deadline(method)
        val outcome = withTimeoutOrNull(deadline) { attempt(run) }
        if (outcome == null) {
            finding(ConformanceFinding(ConformanceRule.DEADLINE, "${method.spiMember} did not answer within $deadline ms (${method.wire})"))
            return CallOutcome.Late
        }
        return outcome
    }

    /** The call's answer or what it threw; cancellation (the deadline) propagates. */
    private suspend fun <T> attempt(run: suspend () -> T): CallOutcome<T> = try {
        CallOutcome.Answered(run())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        CallOutcome.Threw(error)
    }
}
