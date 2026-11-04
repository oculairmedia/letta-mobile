package com.letta.mobile.data.repository.api

import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.coroutines.flow.Flow

/** Bind dismissal to an exact parent without giving the view a repository dependency. */
fun ISubagentRepository.dismissAction(scope: SubagentParentScope?): (suspend (SubagentEntry) -> Result<Unit>)? =
    scope?.let { parent ->
        { entry ->
            if (entry.parentAgentId != parent.parentAgentId || entry.parentConversationId != parent.parentConversationId) {
                Result.failure(IllegalArgumentException("subagent parent scope mismatch"))
            } else {
                dismiss(parent, ToolCallId(entry.toolCallId))
            }
        }
    }

/** Authoritative parent scope for active-subagent projection. */
data class SubagentParentScope(
    val parentAgentId: String,
    val parentConversationId: String,
)

/**
 * Single source of truth for the active-subagent
 * registry carried over the shim's mobile WS (MOBILE_WS_PROTOCOL.md §13).
 *
 * The wire registry is per-socket, but consumers must project it through an
 * explicit parent agent + conversation scope. This prevents two agents whose
 * conversation ids are both `default` from consuming each other's chips.
 */
interface ISubagentRepository {
    /**
     * Hot stream of the active-subagent snapshot for [scope]. The first
     * collection of each distinct scope triggers a scoped `subagent_list`
     * round-trip; later collections of that scope share the cache so no
     * duplicate fetches fire. `subagents_updated` pushes fold in by
     * replacement.
     */
    fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>>

    /** Synchronous cache projection used to avoid an empty initial emission. */
    fun currentActiveSubagents(scope: SubagentParentScope): List<SubagentEntry>

    /** Durably hide one exact dispatch without stopping its worker. */
    suspend fun dismiss(scope: SubagentParentScope, toolCallId: ToolCallId): Result<Unit> =
        Result.failure(UnsupportedOperationException("durable subagent dismissal unavailable"))

    /** Force a fresh `subagent_list` round-trip. */
    suspend fun refresh(): Result<List<SubagentEntry>>

    /**
     * Fetch one subagent's latest TodoWrite snapshot (§13.3), keyed by the
     * parent Agent `tool_call_id`. Point-in-time; not a live tail.
     */
    suspend fun todos(toolCallId: String): Result<List<SubagentTodo>>
}
