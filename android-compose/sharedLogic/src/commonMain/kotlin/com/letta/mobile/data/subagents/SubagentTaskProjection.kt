package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.isBackgroundSubagent

data class SubagentTaskProjection(
    val running: List<SubagentEntry>,
    val finished: List<SubagentEntry>,
)

/**
 * Splits the registry into Running and Finished for the desktop Background
 * tasks panel. letta-mobile-fxoew.4: background work (reflection) is dropped
 * here through the same [isBackgroundSubagent] classifier the Android chrome
 * uses, so both clients hide the same entries.
 */
fun projectSubagentTasks(
    subagents: List<SubagentEntry>,
    clearedKeys: Set<String>,
    keyOf: (SubagentEntry) -> String,
): SubagentTaskProjection {
    val visible = subagents.filterNot { it.isBackgroundSubagent() }
    return SubagentTaskProjection(
        running = visible.filter { it.status == SubagentStatus.RUNNING },
        finished = visible.filter { it.status != SubagentStatus.RUNNING && keyOf(it) !in clearedKeys },
    )
}
