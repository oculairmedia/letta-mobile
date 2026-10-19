package com.letta.mobile.data.runtime

import kotlinx.atomicfu.atomic

/**
 * How a turn's frame collection ended, returned by the collector instead of
 * thrown out of it.
 *
 * It replaces the `TurnCompleted` / `TurnIdleTimedOut` singleton Throwables. Those crossed coroutine
 * boundaries (a settle job or the watchdog is a sibling of the frame loop), so structured
 * concurrency delivered them wrapped in a CancellationException and every abnormal-end catch had to
 * walk the cause chain to tell a clean completion from a real cancel. Being process-wide singletons,
 * each also accumulated `addSuppressed` entries for the life of the process.
 */
internal sealed interface TurnOutcome {
    /** A terminal went out: the server's, the settle window's, a session cut-off's or a local ending's. */
    data object Completed : TurnOutcome

    /** The idle watchdog fired; the caller publishes the Failed terminal. */
    data object IdleTimedOut : TurnOutcome

    /** The inbound stream finished with no terminal and no error. */
    data object StreamEnded : TurnOutcome
}

/**
 * The single outcome of one turn's collection. The first [complete] wins and runs [stopCollecting]
 * (which cancels the frame loop's own job), so whichever coroutine ends the turn (the frame loop,
 * the settle window, the reconnect countdown or the watchdog) stops collection the same way,
 * without throwing.
 */
internal class TurnCompletion(private val stopCollecting: () -> Unit) {
    private val settled = atomic<TurnOutcome?>(null)

    /** The outcome, once the turn has one. */
    val outcome: TurnOutcome? get() = settled.value

    val isSettled: Boolean get() = settled.value != null

    fun complete(outcome: TurnOutcome) {
        if (settled.compareAndSet(null, outcome)) stopCollecting()
    }
}
