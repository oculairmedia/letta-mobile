package com.letta.mobile.data.controller

import com.letta.mobile.data.runtime.LoopPhase
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-bzvro.6 (F06): when to send `sync` for the runtimes a client is watching.
 *
 * - A recovery request ([SyncReason.Gap] from the event-sequence gap detector, or
 *   [SyncReason.Foreground] when the app comes back) syncs every watched runtime, with
 *   `recover_approvals`, at most once per [Policy.minInterval] per runtime: a burst of requests
 *   coalesces into one sync now and at most one more when the window opens.
 * - Otherwise each runtime is re-synced on a cadence that follows its loop: every
 *   [Policy.busyInterval] while the loop is working, every [Policy.idleInterval] while it waits
 *   on input. A periodic sync only refreshes the snapshots (device status, loop status, queue);
 *   it never asks for approval replays.
 *
 * The clock is injected and [tick] is public so the policy is testable without real time.
 */
class RuntimeSyncScheduler(
    private val runtimes: () -> Collection<AppServerRuntimeScope>,
    private val sync: suspend (AppServerRuntimeScope, SyncReason) -> Unit,
    private val clock: () -> Long,
    private val policy: Policy = Policy(),
) {
    data class Policy(
        val minInterval: Duration = 1.seconds,
        val busyInterval: Duration = 5.seconds,
        val idleInterval: Duration = 30.seconds,
        /** How often the cadence is checked; recovery requests wake the loop early. */
        val tick: Duration = 1.seconds,
    )

    enum class SyncReason(val recoverApprovals: Boolean) {
        Gap(recoverApprovals = true),
        Foreground(recoverApprovals = true),
        Periodic(recoverApprovals = false),
    }

    /** [since] is when the runtime was first watched; [lastSyncAt] is null until it has been synced. */
    private class RuntimeCadence(
        val since: Long,
        var lastSyncAt: Long? = null,
        var busy: Boolean = false,
        var pending: SyncReason? = null,
    )

    private data class Key(val agentId: String, val conversationId: String)

    private val lock = SynchronizedObject()
    private val cadences = mutableMapOf<Key, RuntimeCadence>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Ask for a recovery sync of every watched runtime (throttled per [Policy.minInterval]). */
    fun request(reason: SyncReason) {
        synchronized(lock) {
            refreshLocked(clock())
            cadences.values.forEach { it.pending = strongest(it.pending, reason) }
        }
        wake.trySend(Unit)
    }

    /** The runtime's loop reported [status]; working loops are re-synced more often. */
    fun noteLoopStatus(runtime: AppServerRuntimeScope, status: String) {
        val busy = !LoopPhase.fromWire(status).isIdle
        synchronized(lock) {
            cadences.getOrPut(runtime.key()) { RuntimeCadence(since = clock()) }.busy = busy
        }
    }

    /** Sends every sync that is due now. */
    suspend fun tick() {
        for ((runtime, reason) in dueSyncs(clock())) {
            try {
                sync(runtime, reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Telemetry.error(
                    "RuntimeSyncScheduler", "sync.failed", error,
                    "reason" to reason.name,
                    "conversationId" to runtime.conversationId,
                )
            }
        }
    }

    /** Runs the cadence until [scope] is cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            tick()
            select {
                wake.onReceive { }
                onTimeout(policy.tick) { }
            }
        }
    }

    private fun dueSyncs(now: Long): List<Pair<AppServerRuntimeScope, SyncReason>> = synchronized(lock) {
        refreshLocked(now).mapNotNull { runtime ->
            val cadence = cadences[runtime.key()] ?: return@mapNotNull null
            val reason = cadence.dueReason(now) ?: return@mapNotNull null
            cadence.lastSyncAt = now
            cadence.pending = null
            runtime to reason
        }
    }

    private fun RuntimeCadence.dueReason(now: Long): SyncReason? {
        val last = lastSyncAt
        val recovery = pending
        if (recovery != null) return recovery.takeIf { last == null || (now - last).milliseconds >= policy.minInterval }
        val interval = if (busy) policy.busyInterval else policy.idleInterval
        return SyncReason.Periodic.takeIf { (now - (last ?: since)).milliseconds >= interval }
    }

    /** Tracks exactly the watched runtimes: new ones start their cadence now, gone ones are dropped. */
    private fun refreshLocked(now: Long): List<AppServerRuntimeScope> {
        val watched = runtimes().distinctBy { it.key() }
        val keys = watched.mapTo(mutableSetOf()) { it.key() }
        cadences.keys.retainAll(keys)
        watched.forEach { runtime ->
            cadences.getOrPut(runtime.key()) { RuntimeCadence(since = now) }
        }
        return watched
    }

    private fun strongest(current: SyncReason?, next: SyncReason): SyncReason =
        if (current != null && current.recoverApprovals) current else next

    private fun AppServerRuntimeScope.key() = Key(agentId, conversationId)
}
