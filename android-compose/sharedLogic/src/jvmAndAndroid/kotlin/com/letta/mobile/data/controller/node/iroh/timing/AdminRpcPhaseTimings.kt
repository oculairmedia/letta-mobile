package com.letta.mobile.data.controller.node.iroh.timing

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** A part of an admin RPC call whose time is reported beside a slow call, under [attr]. */
internal enum class AdminRpcPhase(val attr: String) {
    /** Conversation discovery before a list is served, its own App Server retrieves included. */
    Discovery("discoveryMs"),

    /** Waiting for an App Server response: the request, the App Server's work and the reply. */
    AppServer("appServerMs"),

    /** Reading the local backend directory on this host. */
    LocalStore("localStoreMs"),
}

/**
 * Per-call phase totals, carried in the handler's coroutine context by the admin RPC router so a slow
 * call can say whether its time went to the App Server or to this host. A phase timed inside another
 * (discovery's App Server retrieves) counts once, under the outer phase.
 */
internal class AdminRpcPhaseTimings(
    private val nanoTime: () -> Long = System::nanoTime,
) : AbstractCoroutineContextElement(Key) {
    private val totalsNs = LongArray(AdminRpcPhase.entries.size)
    private val ran = BooleanArray(AdminRpcPhase.entries.size)
    private var depth = 0

    /** Whole milliseconds per phase that ran during the call, in [AdminRpcPhase] order. */
    fun millis(): Map<AdminRpcPhase, Long> = synchronized(this) {
        AdminRpcPhase.entries.filter { ran[it.ordinal] }.associateWith { totalsNs[it.ordinal] / NANOS_PER_MILLI }
    }

    suspend fun <T> time(phase: AdminRpcPhase, block: suspend () -> T): T {
        val outermost = synchronized(this) { depth++ == 0 }
        val startedNs = nanoTime()
        try {
            return block()
        } finally {
            val tookNs = nanoTime() - startedNs
            synchronized(this) {
                depth--
                if (outermost) {
                    totalsNs[phase.ordinal] += tookNs
                    ran[phase.ordinal] = true
                }
            }
        }
    }

    companion object Key : CoroutineContext.Key<AdminRpcPhaseTimings> {
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Runs [block] as [phase] of the admin RPC call in progress; untimed outside one. */
internal suspend fun <T> timedPhase(phase: AdminRpcPhase, block: suspend () -> T): T {
    val timings = currentCoroutineContext()[AdminRpcPhaseTimings] ?: return block()
    return timings.time(phase, block)
}
