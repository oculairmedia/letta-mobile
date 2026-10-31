package com.letta.mobile.data.subagents

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr

/**
 * Snapshot visibility and bounded absence evidence, protected by the registry's lock.
 * App Server omits silent non-background workers, so an entry must first have appeared
 * in a snapshot before absence can be used to retire it. Evidence is deliberately
 * transient: a host restart cannot turn an old omission into a fresh live observation.
 */
internal class SubagentSnapshotAbsence {
    // A null value means present in the last snapshot; no key means never snapshot-visible.
    private val absentSince = mutableMapOf<SubagentChipKey, Long?>()

    /** Live records whose repeated absence and silence both reached the grace period. */
    fun orphaned(
        records: List<SubagentChipRecord>,
        liveKeys: Set<SubagentChipKey>,
        now: Long,
    ): List<SubagentChipRecord> = records.filter { record ->
        if (record.key in liveKeys) {
            absentSince[record.key] = null
            false
        } else {
            absentPastGrace(record, now)
        }
    }

    private fun absentPastGrace(record: SubagentChipRecord, now: Long): Boolean {
        val visible = record.key in absentSince
        val since = if (visible) {
            absentSince[record.key] ?: now.also { absentSince[record.key] = it }
        } else {
            null
        }
        val absentMs = since?.let { now - it } ?: 0L
        val silentMs = now - record.lastSeenEpochMs
        if (visible && pastGrace(absentMs, silentMs)) return true
        Telemetry.event(
            DurableSubagentRegistry.TAG,
            "reconcile.absenceRetained",
            "conversationId" attr record.conversationId,
            "agentId" attr record.agentId,
            "toolCallId" attr record.toolCallId,
            "snapshotVisible" attr visible,
            "absentMs" attr absentMs,
            "silentMs" attr silentMs,
        )
        return false
    }

    /** A positive sighting of [key] (any accepted observation) restarts its absence clock. */
    fun seen(key: SubagentChipKey) {
        if (key in absentSince) absentSince[key] = null
    }

    /** Forget [key] entirely, e.g. when its record leaves the registry. */
    fun forget(key: SubagentChipKey) {
        absentSince.remove(key)
    }

    /** Forget removed and terminal keys so the evidence cannot outgrow the live registry. */
    fun retainKeys(keys: Set<SubagentChipKey>) {
        absentSince.keys.retainAll(keys)
    }

    /** Both the snapshot absence and the silence have lasted the whole grace period. */
    private fun pastGrace(absentMs: Long, silentMs: Long): Boolean =
        minOf(absentMs, silentMs) >= ABSENCE_GRACE_MS

    private companion object {
        const val ABSENCE_GRACE_MS = 30 * 60_000L
    }
}
