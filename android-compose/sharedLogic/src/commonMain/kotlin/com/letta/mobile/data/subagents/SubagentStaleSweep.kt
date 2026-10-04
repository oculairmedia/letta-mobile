package com.letta.mobile.data.subagents

/**
 * letta-mobile-fxoew.3: the registry's TTL for live chips nobody reports any
 * more.
 *
 * A chip leaves RUNNING only through an App Server lifecycle event or
 * [DurableSubagentRegistry.reconcile]. When neither ever arrives (the worker
 * died with the runtime, the conversation was never reopened), the chip stayed
 * RUNNING forever: the live host held RUNNING chips last seen 5 to 56 days ago,
 * and because live chips are never evicted they pinned the 512-entry cap.
 *
 * A live chip whose `lastSeenEpochMs` is older than `staleAfterMs` becomes
 * [SubagentChipState.CANCELLED] (wire `cancelled`, the documented non-clean
 * terminal) with [SubagentChipRecord.TERMINAL_REASON_STALE]. It is not
 * ORPHANED: orphaning means "the source of truth's snapshot omitted it",
 * staleness means "nothing has mentioned it for a long time". Once terminal it
 * is evictable like any other terminal chip.
 *
 * Pure: returns the terminalized copies and leaves applying them to the caller.
 */
internal fun staleRunningChips(
    records: Collection<SubagentChipRecord>,
    now: Long,
    staleAfterMs: Long,
): List<SubagentChipRecord> = records
    .filter { !it.state.isTerminal && now - it.lastSeenEpochMs > staleAfterMs }
    .map { record ->
        record.copy(
            state = SubagentChipState.CANCELLED,
            terminalAtEpochMs = record.terminalAtEpochMs ?: now,
            terminalReason = SubagentChipRecord.TERMINAL_REASON_STALE,
        )
    }
