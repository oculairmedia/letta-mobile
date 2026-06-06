package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-gnyf7: UI-facing seam for the MAIN/foreground agent's OWN
 * TodoWrite plan in the active conversation. Sister to [ActiveSubagentSource]
 * but for the "self"/"you" entry rather than dispatched subagents.
 *
 * The bar host consumes the self entry exclusively through this interface,
 * so swapping the fake (previews/tests) for the WS-backed
 * [WsSelfTodoSource] is a one-binding change.
 *
 * Contract:
 *  - [selfEntry] emits a synthetic [ActiveSubagent] (id = [ActiveSubagent.SELF_ID],
 *    `isSelf = true`) while the main agent has an ACTIVE plan (at least one
 *    todo that is not completed), else `null`. Snapshot-by-replacement —
 *    every emission is the full current state.
 *  - [todos] returns the latest checklist for the tap-to-todolist sheet.
 */
interface SelfTodoSource {
    fun selfEntry(conversationId: String): Flow<ActiveSubagent?>

    fun todos(conversationId: String): List<SubagentTodo>
}

/**
 * Map a TodoWrite snapshot to the synthetic self [ActiveSubagent], or null
 * when there is no active plan. A plan is "active" when it has at least one
 * item that is not yet completed; an all-completed plan stops pinning the
 * chip (the work is done) — matching the active-only visibility the bar
 * applies to subagents.
 *
 * The description is a compact progress label ("N/M done") so the chip reads
 * as the agent's own running plan at a glance.
 */
internal fun List<SubagentTodo>.toSelfEntry(
    // letta-mobile-dvobc: injected so the self chip's stuck heuristic is
    // testable. Wall-clock epoch-ms stamped as the last-update time whenever
    // the plan changes (each new snapshot is a fresh observation).
    now: Long = System.currentTimeMillis(),
): ActiveSubagent? {
    if (isEmpty()) return null
    val completed = count { it.status.trim().lowercase() == "completed" }
    val total = size
    if (completed >= total) return null
    return ActiveSubagent(
        id = ActiveSubagent.SELF_ID,
        description = "Your plan · $completed/$total done",
        subagentType = "self",
        status = ActiveSubagent.Status.RUNNING,
        isSelf = true,
        // letta-mobile-dvobc: real determinate ring fill from the plan.
        progress = SubagentTodoProgress(completed = completed, total = total),
        lastUpdateAt = now,
    )
}

/**
 * WS-backed [SelfTodoSource]: maps the per-conversation snapshot from
 * [ISelfTodoRepository] (driven by the conversation stream's TodoWrite tool
 * calls) into the synthetic self chip.
 */
class WsSelfTodoSource(
    private val repository: ISelfTodoRepository,
) : SelfTodoSource {
    override fun selfEntry(conversationId: String): Flow<ActiveSubagent?> =
        repository.latestForFlow(conversationId).map { it.toSelfEntry() }

    override fun todos(conversationId: String): List<SubagentTodo> =
        repository.latestFor(conversationId)
}

/**
 * In-memory, fully-controllable [SelfTodoSource] for previews and tests.
 */
class FakeSelfTodoSource(
    initial: List<SubagentTodo> = emptyList(),
) : SelfTodoSource {
    private val _todos = MutableStateFlow(initial)
    val todosFlow: StateFlow<List<SubagentTodo>> = _todos.asStateFlow()

    fun setTodos(todos: List<SubagentTodo>) {
        _todos.value = todos
    }

    override fun selfEntry(conversationId: String): Flow<ActiveSubagent?> =
        _todos.map { it.toSelfEntry() }

    override fun todos(conversationId: String): List<SubagentTodo> = _todos.value
}
