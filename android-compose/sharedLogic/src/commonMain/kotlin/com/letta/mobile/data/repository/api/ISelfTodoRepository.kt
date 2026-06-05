package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.SubagentTodo
import kotlinx.coroutines.flow.Flow

/**
 * letta-mobile-gnyf7: source of truth for the MAIN/foreground agent's OWN
 * TodoWrite plan in the active conversation.
 *
 * Unlike [ISubagentRepository] (which tracks DISPATCHED subagents over the
 * shim's dedicated subagent registry, MOBILE_WS_PROTOCOL.md §13), the
 * primary conversation agent's TodoWrite calls are NOT carried by that
 * registry — they arrive as ordinary `tool_call_message` frames
 * (`name == "TodoWrite"`) in the conversation stream. This repository
 * observes that stream directly and surfaces the latest snapshot per
 * conversation so the UI can pin a distinct "self"/"you" entry in the same
 * active-subagent bar.
 *
 * The snapshot is keyed by `conversationId`. [latestForFlow] emits the full
 * current todo list for a conversation on every change
 * (snapshot-by-replacement), so the UI reduces without per-frame rebuilds —
 * matching the cron/subagent convention and the rmzmo perf constraints.
 */
interface ISelfTodoRepository {
    /**
     * Hot stream of the main agent's latest TodoWrite snapshot for
     * [conversationId]. Emits an empty list until a TodoWrite tool call is
     * seen on that conversation. Each emission is the FULL todo list, never
     * a delta.
     */
    fun latestForFlow(conversationId: String): Flow<List<SubagentTodo>>

    /**
     * Point-in-time read of the latest TodoWrite snapshot for
     * [conversationId] (used by the tap-to-todolist sheet). Returns an empty
     * list when no plan has been observed yet.
     */
    fun latestFor(conversationId: String): List<SubagentTodo>
}
