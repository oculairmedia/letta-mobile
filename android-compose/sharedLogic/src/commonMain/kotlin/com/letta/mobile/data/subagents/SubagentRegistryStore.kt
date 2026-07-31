package com.letta.mobile.data.subagents

/**
 * Durable backing for [DurableSubagentRegistry] (letta-mobile-lgns8.22.8).
 *
 * Deliberately tiny and whole-snapshot: the registry is bounded
 * ([DurableSubagentRegistry.MAX_ENTRIES]) so rewriting the whole set on each
 * mutation is cheap, and a whole-file atomic replace is the only shape that
 * cannot leave a half-written registry behind. This mirrors the existing
 * controller persistence precedent (`FilePairedPeerStore`) — NO new database.
 *
 * Implementations must be safe to call from multiple threads; the registry
 * calls them while holding its own lock.
 */
interface SubagentRegistryStore {
    /** Rehydrate. Must return an empty list (never throw) when nothing is stored. */
    fun load(): List<SubagentChipRecord>

    /** Replace the persisted set with [records]. Must be atomic w.r.t. readers. */
    fun save(records: List<SubagentChipRecord>)
}

/**
 * Non-durable store. Useful for tests and for callers that genuinely do not
 * want restart survival (e.g. an ephemeral CLI probe).
 */
class InMemorySubagentRegistryStore(
    initial: List<SubagentChipRecord> = emptyList(),
) : SubagentRegistryStore {
    private var records: List<SubagentChipRecord> = initial

    override fun load(): List<SubagentChipRecord> = records

    override fun save(records: List<SubagentChipRecord>) {
        this.records = records
    }
}
