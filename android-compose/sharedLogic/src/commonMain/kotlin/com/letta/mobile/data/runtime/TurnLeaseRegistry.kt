package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.update

/**
 * letta-mobile-8xxzv: the App Server's runtime key — `{agent_id, conversation_id}`.
 *
 * letta-code's App Server (0.29.9 / 0.29.12, `src/websocket/listener/runtime.ts`)
 * keeps a `conversationRuntimes` map with a per-conversation TurnLifecycle, queue
 * and pump, and `message-router.ts` routes `create_message` without any global
 * gate. The documented contract is therefore: AT MOST ONE active turn per
 * `{agent, conversation}` runtime, PARALLEL across runtimes. This key is the
 * client-side expression of that contract.
 */
data class TurnRuntimeKey(val agentId: String, val conversationId: String) {
    override fun toString(): String = "$agentId/$conversationId"
}

/**
 * letta-mobile-8xxzv: ALL per-turn engine state for ONE runtime key.
 *
 * Before this bead every field here was a single process-wide slot on
 * [AppServerTurnEngine], so a turn on conversation B was rejected
 * ("iroh_turn_engine_busy") purely because conversation A held the one lease —
 * the last unkeyed slot of the letta-mobile-or40x defect class (the transport
 * was keyed in PR #1055, the coordinator in PR #1056).
 *
 * Atomic fields are private and reached through accessors: kotlinx-atomicfu's
 * JVM transformer rejects an atomic field touched from another class.
 */
internal class TurnLeaseSlot(val key: TurnRuntimeKey) {
    private val leaseRef = atomic<TurnLease?>(null)
    private val ownerRef = atomic<AppServerTurnEngine.ActiveTurnOwner?>(null)

    /**
     * Run-id supersession gate for THIS key only. Keeping one gate per key is
     * what stops conversation A's mid-turn run-id promotion from rejecting
     * conversation B's frames.
     */
    val runIdGate = TurnRunIdGate(leaseRef, ownerRef)

    /** Cached `runtime_start` scope for this key (was one global slot). */
    private val runtimeScopeRef = atomic<AppServerRuntimeScope?>(null)

    var lease: TurnLease?
        get() = leaseRef.value
        set(value) { leaseRef.value = value }

    fun casLease(expect: TurnLease?, update: TurnLease?): Boolean =
        leaseRef.compareAndSet(expect, update)

    fun updateLease(block: (TurnLease?) -> TurnLease?) = leaseRef.update(block)

    var owner: AppServerTurnEngine.ActiveTurnOwner?
        get() = ownerRef.value
        set(value) { ownerRef.value = value }

    fun casOwner(
        expect: AppServerTurnEngine.ActiveTurnOwner?,
        update: AppServerTurnEngine.ActiveTurnOwner?,
    ): Boolean = ownerRef.compareAndSet(expect, update)

    fun updateOwner(
        block: (AppServerTurnEngine.ActiveTurnOwner?) -> AppServerTurnEngine.ActiveTurnOwner?,
    ) = ownerRef.update(block)

    var runtimeScope: AppServerRuntimeScope?
        get() = runtimeScopeRef.value
        set(value) { runtimeScopeRef.value = value }

    /** A lease is held (Preparing … Streaming/Retiring) but has not gone Terminal. */
    val isBusy: Boolean
        get() = leaseRef.value?.let { it.phase != TurnLeasePhase.Terminal } ?: false

    /** True while this key owns any lease at all — never evict such an entry. */
    val isTracking: Boolean get() = leaseRef.value != null
}

/**
 * letta-mobile-8xxzv: bounded, recency-ordered registry of [TurnLeaseSlot]s.
 *
 * Mirrors the ChatSendCoordinator reference implementation merged in PR #1056:
 * cap [MAX_TRACKED_RUNTIME_KEYS], evict SETTLED entries oldest-first, NEVER drop
 * a tracking entry (the map exceeds the cap instead, telemetered), and re-insert
 * on access so ordering is recency rather than first-seen.
 */
internal class TurnLeaseRegistry(private val cap: Int = MAX_TRACKED_RUNTIME_KEYS) {
    private val lock = SynchronizedObject()
    private val slots = linkedMapOf<TurnRuntimeKey, TurnLeaseSlot>()

    fun slotFor(key: TurnRuntimeKey): TurnLeaseSlot = synchronized(lock) {
        val slot = slots.remove(key) ?: TurnLeaseSlot(key)
        slots[key] = slot
        evictOverflowLocked(keep = key)
        slot
    }

    fun peek(key: TurnRuntimeKey): TurnLeaseSlot? = synchronized(lock) { slots[key] }

    fun snapshot(): List<TurnLeaseSlot> = synchronized(lock) { slots.values.toList() }

    fun trackedCount(): Int = synchronized(lock) { slots.size }

    /** Every key whose lease is currently held. */
    fun busySlots(): List<TurnLeaseSlot> = snapshot().filter { it.isBusy }

    private fun evictOverflowLocked(keep: TurnRuntimeKey) {
        if (slots.size <= cap) return
        var overflow = slots.size - cap
        val settledOldestFirst = slots.entries
            .filter { it.key != keep && !it.value.isTracking }
            .map { it.key }
        for (victim in settledOldestFirst) {
            if (overflow <= 0) break
            slots.remove(victim)
            overflow -= 1
            Telemetry.event(
                "AppServerTurnEngine", "leaseSlot.evicted",
                "key" to victim.toString(),
                "tracked" to slots.size,
                "cap" to cap,
            )
        }
        if (slots.size > cap) {
            // SENSING: every remaining entry holds a live lease, so the cap
            // yields rather than costing a running turn its state.
            Telemetry.event(
                "AppServerTurnEngine", "leaseSlot.capExceededByLiveTurns",
                "tracked" to slots.size,
                "cap" to cap,
                level = Telemetry.Level.WARN,
            )
        }
    }

    companion object {
        /** Same cap as ChatSendCoordinator's turn-state map (PR #1056). */
        const val MAX_TRACKED_RUNTIME_KEYS: Int = 32
    }
}
