package com.letta.mobile.data.presence

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.isBackgroundSubagent

/**
 * What one conversation's subagent registry entries say about it: how many foreground subagents
 * are running (its tool call is DELEGATING), and whether a background dream (reflection) or
 * memory-upkeep run is in flight.
 */
data class SubagentActivity(val running: Int = 0, val dreaming: Boolean = false) {
    companion object {
        val NONE = SubagentActivity()

        /** The activity of [entries], all of which belong to one conversation. */
        fun of(entries: List<SubagentEntry>): SubagentActivity {
            val live = entries.filter { it.status == SubagentStatus.RUNNING }
            if (live.isEmpty()) return NONE
            return SubagentActivity(
                running = live.count { !it.isBackgroundSubagent() },
                dreaming = live.any { it.isBackgroundSubagent() },
            )
        }
    }
}

/**
 * The conversation each entry runs under, from the provenance the host stamps on it. Entries
 * without both halves of their parent scope cannot be attributed and are left out.
 */
internal fun subagentsByConversation(entries: List<SubagentEntry>): Map<RunScope, List<SubagentEntry>> = entries
    .filter { !it.parentConversationId.isNullOrBlank() && !it.parentAgentId.isNullOrBlank() }
    .groupBy { RunScope(conversationId = it.parentConversationId.orEmpty(), agentId = it.parentAgentId.orEmpty()) }
