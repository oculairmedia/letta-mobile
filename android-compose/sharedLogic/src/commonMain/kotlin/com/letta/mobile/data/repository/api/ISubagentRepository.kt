package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.coroutines.flow.Flow

/**
 * letta-mobile-73o2h.3: single source of truth for the active-subagent
 * registry carried over the shim's mobile WS (MOBILE_WS_PROTOCOL.md §13).
 *
 * The registry is per-socket (NOT per-agent): there is one shared snapshot
 * of active subagents for the live connection. [activeSubagentsFlow] emits
 * the full current set on every change (snapshot-by-replacement), so the
 * UI reduces without per-frame rebuilds — matching the cron convention and
 * the rmzmo perf constraints.
 */
interface ISubagentRepository {
    /**
     * Hot stream of the active-subagent snapshot. The first subscriber
     * triggers a `subagent_list` round-trip; subsequent subscribers share
     * the same flow so no duplicate fetches fire. `subagents_updated`
     * pushes fold in by replacement.
     */
    fun activeSubagentsFlow(): Flow<List<SubagentEntry>>

    /** Force a fresh `subagent_list` round-trip. */
    suspend fun refresh(): Result<List<SubagentEntry>>

    /**
     * Fetch one subagent's latest TodoWrite snapshot (§13.3), keyed by the
     * parent Agent `tool_call_id`. Point-in-time; not a live tail.
     */
    suspend fun todos(toolCallId: String): Result<List<SubagentTodo>>
}
