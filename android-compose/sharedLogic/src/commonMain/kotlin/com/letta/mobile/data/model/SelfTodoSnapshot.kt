package com.letta.mobile.data.model

data class SelfTodoSnapshot(
    val todos: List<SubagentTodo> = emptyList(),
    val lifecycleStatus: String? = null,
)

data class SelfTodoPlanState(
    val completed: Int,
    val total: Int,
)

fun SelfTodoSnapshot.toActivePlanState(): SelfTodoPlanState? {
    if (todos.isEmpty() || lifecycleStatus.isTerminalSubagentStatus()) return null
    val completed = todos.count { it.status.trim().lowercase() == SubagentStatus.COMPLETED }
    return if (completed < todos.size) SelfTodoPlanState(completed, todos.size) else null
}

private fun String?.isTerminalSubagentStatus(): Boolean = when (this?.trim()?.lowercase()) {
    SubagentStatus.COMPLETED,
    SubagentStatus.FAILED,
    SubagentStatus.CANCELLED,
    -> true
    else -> false
}
