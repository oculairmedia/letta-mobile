package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.api.ISubagentRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-73o2h.3: the WS-backed [ActiveSubagentSource]. This is the
 * one-class change the .2 seam was designed for — it maps the per-socket
 * subagent registry ([ISubagentRepository], MOBILE_WS_PROTOCOL.md §13) into
 * the UI-facing [ActiveSubagent] model the bottom status bar renders.
 *
 * Snapshot-by-replacement is preserved: the repository emits the full active
 * set on every change (initial `subagent_list` + folded `subagents_updated`
 * pushes), and we map each whole snapshot to an [ImmutableList]. There are no
 * per-frame full rebuilds — the bar reduces by simple replacement, matching
 * the production reducer/render conventions and the rmzmo perf constraints.
 *
 * Mapping rules (§13.2):
 *  - id = `toolCallId` (the canonical correlation key), falling back to
 *    `taskId` when `toolCallId` is blank (background dispatches that only
 *    carry a task id), then to a synthetic key as a last resort so the chip
 *    still gets a stable slot.
 *  - status string → [ActiveSubagent.Status]; unknown values are treated as
 *    [ActiveSubagent.Status.RUNNING] so a forward-compat shim status still
 *    surfaces in the active bar rather than silently vanishing.
 */
class WsActiveSubagentSource(
    private val repository: ISubagentRepository,
    scope: CoroutineScope,
) : ActiveSubagentSource {

    override suspend fun todos(toolCallId: String) = repository.todos(toolCallId)

    override val activeSubagents: StateFlow<ImmutableList<ActiveSubagent>> =
        repository.activeSubagentsFlow()
            .map { entries -> entries.map { it.toActiveSubagent() }.toImmutableList() }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = persistentListOf(),
            )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        internal fun SubagentEntry.toActiveSubagent(): ActiveSubagent = ActiveSubagent(
            id = toolCallId.takeIf { it.isNotBlank() }
                ?: taskId?.takeIf { it.isNotBlank() }
                ?: "subagent-${hashCode()}",
            description = description,
            subagentType = subagentType,
            status = status.toActiveSubagentStatus(),
        )

        internal fun String.toActiveSubagentStatus(): ActiveSubagent.Status = when (this) {
            SubagentStatus.RUNNING -> ActiveSubagent.Status.RUNNING
            SubagentStatus.COMPLETED -> ActiveSubagent.Status.COMPLETED
            SubagentStatus.FAILED -> ActiveSubagent.Status.FAILED
            // Forward-compat: an unrecognized status keeps the chip visible
            // (treated as still-running) rather than being filtered out.
            else -> ActiveSubagent.Status.RUNNING
        }
    }
}
