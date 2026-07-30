package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus

data class SubagentTaskProjection(
    val running: List<SubagentEntry>,
    val finished: List<SubagentEntry>,
)

fun projectSubagentTasks(
    subagents: List<SubagentEntry>,
    clearedKeys: Set<String>,
    keyOf: (SubagentEntry) -> String,
): SubagentTaskProjection = SubagentTaskProjection(
    running = subagents.filter { it.status == SubagentStatus.RUNNING },
    finished = subagents.filter { it.status != SubagentStatus.RUNNING && keyOf(it) !in clearedKeys },
)
