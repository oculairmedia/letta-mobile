package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.coroutines.flow.Flow

/** Authoritative parent scope for active-subagent projection. */
data class SubagentParentScope(
    val parentAgentId: String,
    val parentConversationId: String,
)

/**
 * letta-mobile-73o2h.3: single source of truth for the active-subagent
 * registry carried over the shim's mobile WS (MOBILE_WS_PROTOCOL.md §13).
 *
 * The wire registry is per-socket, but consumers must project it through an
 * explicit parent agent + conversation scope. This prevents two agents whose
 * conversation ids are both `default` from consuming each other's chips.
 */
interface ISubagentRepository {
    /**
     * Hot stream of the active-subagent snapshot. The first subscriber
     * triggers a `subagent_list` round-trip; subsequent subscribers share
     * the same flow so no duplicate fetches fire. `subagents_updated`
     * pushes fold in by replacement.
     */
    fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>>

    /** Synchronous cache projection used to avoid an empty initial emission. */
    fun currentActiveSubagents(scope: SubagentParentScope): List<SubagentEntry>

    /** Force a fresh `subagent_list` round-trip. */
    suspend fun refresh(): Result<List<SubagentEntry>>

    /**
     * Fetch one subagent's latest TodoWrite snapshot (§13.3), keyed by the
     * parent Agent `tool_call_id`. Point-in-time; not a live tail.
     */
    suspend fun todos(toolCallId: String): Result<List<SubagentTodo>>
}
