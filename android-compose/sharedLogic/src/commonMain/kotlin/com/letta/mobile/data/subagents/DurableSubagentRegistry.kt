package com.letta.mobile.data.subagents

import com.letta.mobile.util.Telemetry
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-lgns8.22.8 — the durable, keyed subagent-chip registry.
 *
 * ## The defect class
 * Same shape as letta-mobile-or40x: durable state that should be KEYED lived
 * in one process-wide, in-RAM slot. For subagent chips this produced two
 * complementary failures:
 *  - restart the controller and every chip vanishes while its worker keeps
 *    running (a "completed" that never happened), and
 *  - a chip whose worker died has nothing that can ever clear it, so it spins
 *    forever (letta-mobile-7vs4s' production symptom).
 *
 * ## What this owns
 *  1. **Durable, keyed state.** Chips are keyed by
 *     ([SubagentChipKey]: conversationId, agentId, toolCallId) and written
 *     through a [SubagentRegistryStore] on every mutation, so a restart
 *     rehydrates rather than starting empty. Two parents never share a slot.
 *  2. **Source precedence** ([SubagentChipSource], letta-mobile-7vs4s): a
 *     weaker producer can never overwrite a stronger one's fact.
 *  3. **Lifecycle enforcement** ([SubagentChipState], letta-mobile-5hihw):
 *     illegal transitions are rejected + telemetered, never applied.
 *  4. **Reconciliation** ([reconcile]): on restart / reconnect, persisted chips
 *     with no live counterpart become [SubagentChipState.ORPHANED] with
 *     telemetry — never silently vanish, never zombie forever.
 *  5. **Boundedness** ([MAX_ENTRIES]): terminal chips are evicted oldest-first
 *     with telemetry; **live chips are never evicted** (same precedent as
 *     `TurnLeaseRegistry` / `InboundControlRequestRegistry`).
 *  6. **Replay idempotence** ([replaySnapshot]): re-delivering the snapshot
 *     after a reconnect converges by chip id instead of duplicating.
 *
 * All public methods are thread-safe (blocking [SynchronizedObject], the same
 * primitive `RuntimeEventFanout` / `InboundControlRequestRegistry` use, so
 * this stays platform-neutral commonMain code with no `java.*`).
 */
class DurableSubagentRegistry(
    private val store: SubagentRegistryStore = InMemorySubagentRegistryStore(),
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()

    /** Insertion-ordered so eviction and snapshots are deterministic. */
    private val entries = LinkedHashMap<SubagentChipKey, SubagentChipRecord>()

    init {
        rehydrate()
    }

    /**
     * Load persisted chips into memory. Invoked from `init`; a corrupt or
     * unreadable store degrades to empty (telemetered) rather than preventing
     * the controller from starting.
     */
    private fun rehydrate() {
        val loaded = runCatching { store.load() }.getOrElse { failure ->
            Telemetry.error(TAG, "rehydrate.failed", failure)
            emptyList()
        }
        synchronized(lock) {
            entries.clear()
            loaded.forEach { entries[it.key] = it }
        }
        if (loaded.isNotEmpty()) {
            Telemetry.event(
                TAG,
                "rehydrated",
                "chips" to loaded.size,
                "live" to loaded.count { !it.state.isTerminal },
            )
        }
    }

    // ---------------------------------------------------------------- observe

    sealed class ObserveResult {
        /** Applied. [record] is the post-merge state. */
        data class Accepted(val record: SubagentChipRecord) : ObserveResult()

        /**
         * letta-mobile-7vs4s: a lower-precedence source tried to overwrite a
         * higher-precedence fact. The existing record is untouched.
         */
        data class RejectedBySource(
            val existing: SubagentChipRecord,
            val attempted: SubagentChipObservation,
        ) : ObserveResult()

        /**
         * letta-mobile-5hihw: the observation asked for an edge the lifecycle
         * state machine does not have. The existing record is untouched.
         */
        data class IllegalTransition(
            val existing: SubagentChipRecord,
            val attempted: SubagentChipObservation,
        ) : ObserveResult()
    }

    /**
     * Record one producer's view of a chip.
     *
     * Idempotent by [SubagentChipKey]: re-observing the same chip merges
     * (last-seen bump + backfill of still-empty provenance) instead of
     * appending a duplicate — which is what makes reconnect replay safe.
     */
    fun observe(observation: SubagentChipObservation): ObserveResult {
        val result = synchronized(lock) { observeLocked(observation) }
        when (result) {
            is ObserveResult.Accepted -> Unit
            is ObserveResult.RejectedBySource -> Telemetry.event(
                TAG,
                "source.rejected",
                "conversationId" to observation.conversationId,
                "toolCallId" to observation.toolCallId,
                "attemptedSource" to observation.source.name,
                "attemptedState" to observation.state.name,
                "heldBySource" to result.existing.source.name,
                "heldState" to result.existing.state.name,
                level = Telemetry.Level.WARN,
            )
            is ObserveResult.IllegalTransition -> Telemetry.event(
                TAG,
                "lifecycle.illegalTransition",
                "conversationId" to observation.conversationId,
                "toolCallId" to observation.toolCallId,
                "from" to result.existing.state.name,
                "to" to observation.state.name,
                "source" to observation.source.name,
                level = Telemetry.Level.WARN,
            )
        }
        return result
    }

    private fun observeLocked(observation: SubagentChipObservation): ObserveResult {
        val now = clock()
        val existing = entries[observation.key]
            ?: return ObserveResult.Accepted(insertLocked(observation, now))

        // letta-mobile-7vs4s: precedence is checked BEFORE the lifecycle edge so
        // a stale weak source can neither advance nor rewind a strong fact.
        if (observation.source.precedence < existing.source.precedence) {
            return ObserveResult.RejectedBySource(existing, observation)
        }
        // letta-mobile-5hihw: no producer, however authoritative, may take an
        // edge the state machine does not have (notably terminal → running).
        if (!existing.state.canAdvanceTo(observation.state)) {
            return ObserveResult.IllegalTransition(existing, observation)
        }

        val merged = existing.mergedWith(observation, now)
        entries[observation.key] = merged
        persistLocked()
        return ObserveResult.Accepted(merged)
    }

    private fun insertLocked(observation: SubagentChipObservation, now: Long): SubagentChipRecord {
        val record = SubagentChipRecord(
            conversationId = observation.conversationId,
            agentId = observation.agentId,
            toolCallId = observation.toolCallId,
            state = observation.state,
            source = observation.source,
            description = observation.description,
            subagentType = observation.subagentType,
            taskId = observation.taskId,
            subagentAgentId = observation.subagentAgentId,
            subagentConversationId = observation.subagentConversationId,
            parentRunId = observation.parentRunId,
            startedAt = observation.startedAt,
            generation = observation.generation,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
            terminalAtEpochMs = if (observation.state.isTerminal) now else null,
        )
        entries[observation.key] = record
        enforceCapacityLocked()
        persistLocked()
        return record
    }

    /**
     * Merge an accepted observation onto an existing record. Provenance fields
     * BACKFILL only (never clobber a populated value with an empty one — same
     * conservative fold as `SubagentCorrelator`), and [terminalAtEpochMs] is
     * stamped exactly once, on the first terminal transition.
     */
    private fun SubagentChipRecord.mergedWith(
        observation: SubagentChipObservation,
        now: Long,
    ): SubagentChipRecord = copy(
        state = observation.state,
        source = observation.source,
        description = description.ifEmpty { observation.description },
        subagentType = subagentType.ifEmpty { observation.subagentType },
        taskId = taskId ?: observation.taskId,
        subagentAgentId = subagentAgentId ?: observation.subagentAgentId,
        subagentConversationId = subagentConversationId ?: observation.subagentConversationId,
        parentRunId = parentRunId ?: observation.parentRunId,
        startedAt = startedAt ?: observation.startedAt,
        generation = maxOf(generation, observation.generation),
        lastSeenEpochMs = now,
        terminalAtEpochMs = terminalAtEpochMs
            ?: now.takeIf { observation.state.isTerminal },
    )

    // ------------------------------------------------------------- reconcile

    data class ReconcileResult(
        val orphaned: List<SubagentChipRecord>,
        val liveRetained: Int,
    )

    /**
     * Reconcile persisted chips for [conversationId] against the authoritative
     * live set.
     *
     * [liveToolCallIds] is the source of truth (the App Server's own
     * `update_subagent_state` / `subagent.list` view). Every persisted chip in
     * this conversation that is **not** in that set and is **not** already
     * terminal moves to [SubagentChipState.ORPHANED] with WARN telemetry.
     *
     * This is the restart contract: a chip is never dropped just because the
     * new process has not heard of it (the pre-bead behaviour was to delete
     * unseen keys outright), and it is never left spinning either.
     *
     * Reconciliation is a privileged internal transition — it bypasses
     * [SubagentChipSource] precedence, because "the source of truth does not
     * know about you" outranks any producer's claim. It still respects the
     * lifecycle machine: already-terminal chips are left exactly as they are.
     */
    fun reconcile(
        conversationId: String,
        liveToolCallIds: Set<String>,
        generation: Long = 0,
    ): ReconcileResult {
        val result = synchronized(lock) { reconcileLocked(conversationId, liveToolCallIds, generation) }
        reportReconciled(conversationId, generation, result)
        return result
    }

    private fun reconcileLocked(
        conversationId: String,
        liveToolCallIds: Set<String>,
        generation: Long,
    ): ReconcileResult {
        val now = clock()
        val scoped = entries.values.filter { it.conversationId == conversationId }
        val (live, missing) = scoped.filter { !it.state.isTerminal }
            .partition { it.toolCallId in liveToolCallIds }
        val orphaned = missing.map { record ->
            record.copy(
                state = SubagentChipState.ORPHANED,
                generation = maxOf(record.generation, generation),
                lastSeenEpochMs = now,
                terminalAtEpochMs = record.terminalAtEpochMs ?: now,
            ).also { entries[it.key] = it }
        }
        if (orphaned.isNotEmpty()) persistLocked()
        return ReconcileResult(orphaned = orphaned, liveRetained = live.size)
    }

    private fun reportReconciled(conversationId: String, generation: Long, result: ReconcileResult) {
        result.orphaned.forEach { record ->
            Telemetry.event(
                TAG,
                "reconcile.orphaned",
                "conversationId" to record.conversationId,
                "agentId" to (record.agentId ?: ""),
                "toolCallId" to record.toolCallId,
                "source" to record.source.name,
                "generation" to record.generation,
                level = Telemetry.Level.WARN,
            )
        }
        if (result.orphaned.isEmpty() && result.liveRetained == 0) return
        Telemetry.event(
            TAG,
            "reconciled",
            "conversationId" to conversationId,
            "orphaned" to result.orphaned.size,
            "liveRetained" to result.liveRetained,
            "generation" to generation,
        )
    }

    /**
     * letta-mobile-5hihw: the parent assistant turn finished.
     *
     * This is intentionally a NO-OP on chip lifecycle. It exists so the seam is
     * named and testable: the parent turn ending is NOT evidence that a
     * background subagent finished, so running chips stay running (and keep
     * rendering "running in background") until an authoritative subagent
     * lifecycle event or [reconcile] moves them.
     *
     * @return the chips deliberately left non-terminal.
     */
    fun markParentTurnEnded(conversationId: String): List<SubagentChipRecord> {
        val retained = synchronized(lock) {
            entries.values.filter { it.conversationId == conversationId && !it.state.isTerminal }
        }
        if (retained.isNotEmpty()) {
            Telemetry.event(
                TAG,
                "parentTurnEnded.runningRetained",
                "conversationId" to conversationId,
                "retained" to retained.size,
            )
        }
        return retained
    }

    // -------------------------------------------------------------- snapshots

    /** Chips for [conversationId]; terminal chips included only when asked. */
    fun snapshot(conversationId: String, includeTerminal: Boolean): List<SubagentChipRecord> =
        synchronized(lock) {
            entries.values
                .filter { it.conversationId == conversationId }
                .filter { includeTerminal || !it.state.isTerminal }
                .toList()
        }

    /**
     * Snapshot for replay-on-reconnect fanout.
     *
     * A pure read of durable state: replaying it any number of times converges
     * on the same chip set, because a client (or this registry, via [observe])
     * merges by [SubagentChipKey] rather than appending. Terminal chips are
     * included so a chip that finished while the client was disconnected
     * resurfaces as terminal instead of disappearing (letta-mobile-29h9u
     * lingering terminal chips).
     */
    fun replaySnapshot(conversationId: String): List<SubagentChipRecord> =
        snapshot(conversationId, includeTerminal = true)

    fun record(conversationId: String, agentId: String?, toolCallId: String): SubagentChipRecord? =
        synchronized(lock) { entries[SubagentChipKey(conversationId, agentId, toolCallId)] }

    /** First chip matching (conversation, toolCallId) regardless of agent scope. */
    fun findByToolCall(conversationId: String, toolCallId: String): SubagentChipRecord? =
        synchronized(lock) {
            entries.values.firstOrNull {
                it.conversationId == conversationId && it.toolCallId == toolCallId
            }
        }

    /** Test/telemetry: total retained chips across all conversations. */
    fun size(): Int = synchronized(lock) { entries.size }

    /** Test/telemetry: retained chips that are still live (never evictable). */
    fun liveCount(): Int = synchronized(lock) { entries.values.count { !it.state.isTerminal } }

    // ------------------------------------------------------------- boundedness

    /**
     * Evict terminal chips oldest-first until the registry fits [maxEntries].
     *
     * LIVE CHIPS ARE NEVER EVICTED — evicting a running chip is exactly the
     * "silently vanish" failure this bead exists to remove. If the whole
     * registry is live and over cap we exceed the cap deliberately and record
     * it at WARN (precedent: `TurnLeaseRegistry` / `ChatSendCoordinator`).
     */
    private fun enforceCapacityLocked() {
        if (entries.size <= maxEntries) return
        val evictable = entries.values
            .filter { it.state.isTerminal }
            .sortedBy { it.terminalAtEpochMs ?: it.lastSeenEpochMs }
            .iterator()
        val evicted = mutableListOf<SubagentChipRecord>()
        while (entries.size > maxEntries && evictable.hasNext()) {
            val victim = evictable.next()
            entries.remove(victim.key)
            evicted += victim
        }
        evicted.forEach { victim ->
            Telemetry.event(
                TAG,
                "capacity.evicted",
                "conversationId" to victim.conversationId,
                "toolCallId" to victim.toolCallId,
                "state" to victim.state.name,
                "cap" to maxEntries,
                level = Telemetry.Level.WARN,
            )
        }
        if (entries.size > maxEntries) {
            Telemetry.event(
                TAG,
                "capacity.allLive",
                "size" to entries.size,
                "cap" to maxEntries,
                level = Telemetry.Level.WARN,
            )
        }
    }

    private fun persistLocked() {
        runCatching { store.save(entries.values.toList()) }
            .onFailure { Telemetry.error(TAG, "persist.failed", it) }
    }

    /** Test/bootstrap hook: drop everything, including the persisted copy. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            persistLocked()
        }
    }

    companion object {
        const val TAG = "DurableSubagentRegistry"

        /**
         * Bound on retained chips. Sized well above any plausible fan-out of
         * concurrent + recently-terminal subagents for one controller; an
         * eviction is recorded at WARN because it means chip history was lost.
         */
        const val MAX_ENTRIES = 512
    }
}
