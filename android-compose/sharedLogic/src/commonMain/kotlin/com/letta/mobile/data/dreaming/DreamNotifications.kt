package com.letta.mobile.data.dreaming

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentKind
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.storage.SecureSettingsStore

/** One dream that ended, for a "dream finished" notification. */
data class DreamFinished(
    val agentId: String,
    val description: String,
    val succeeded: Boolean,
)

/**
 * Turns successive subagent registry snapshots into finished dreams. Only an
 * explicit terminal status counts: a run that merely drops out of a snapshot may still be running
 * (the registry retains and re-verifies those), so it is never announced as finished.
 */
class DreamFinishTracker {
    private var running: Map<String, SubagentEntry> = emptyMap()

    /** The dreams that were running in the previous snapshot and are terminal in [entries]. */
    fun update(entries: List<SubagentEntry>): List<DreamFinished> {
        val dreams = entries.filter { SubagentKind.fromWire(it.subagentType).isDream }
        val finished = dreams.mapNotNull { entry ->
            val was = running[entry.toolCallId] ?: return@mapNotNull null
            if (entry.status == SubagentStatus.RUNNING) return@mapNotNull null
            val agentId = entry.parentAgentId ?: was.parentAgentId ?: return@mapNotNull null
            DreamFinished(agentId, entry.description.ifBlank { was.description }, succeeded = entry.status == SubagentStatus.COMPLETED)
        }
        val stillRunning = dreams.filter { it.status == SubagentStatus.RUNNING }.associateBy { it.toolCallId }
        // A run absent from this snapshot stays tracked until a snapshot says how it ended.
        val absent = running.filterKeys { key -> dreams.none { it.toolCallId == key } }
        running = absent + stillRunning
        return finished
    }
}

/** The per-agent "tell me when a dream finishes" opt-in, off by default. */
class DreamNotifyPrefs(private val store: SecureSettingsStore) {
    fun isEnabled(agentId: String): Boolean = store.getString(key(agentId)) == ENABLED

    fun setEnabled(agentId: String, enabled: Boolean) {
        if (enabled) store.putString(key(agentId), ENABLED) else store.remove(key(agentId))
    }

    private fun key(agentId: String) = "$PREFIX$agentId"

    private companion object {
        const val PREFIX = "letta.dreaming.notify."
        const val ENABLED = "true"
    }
}
