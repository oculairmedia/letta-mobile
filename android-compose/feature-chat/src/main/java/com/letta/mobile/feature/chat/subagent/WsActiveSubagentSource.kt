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
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-73o2h.3: the WS-backed [ActiveSubagentSource]. This is the
 * one-class change the .2 seam was designed for — it maps the per-socket
 * subagent registry ([ISubagentRepository], MOBILE_WS_PROTOCOL.md §13) into
 * the UI-facing [ActiveSubagent] model the bottom status bar renders.
 *
 * Push snapshots can be incomplete during reconnect/registry gaps, so the
 * source retains previously observed RUNNING entries across omissions and only
 * removes them when a terminal status is explicitly observed. There are no
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
 *  - letta-mobile-vo9y1: `subagentAgentId` (`agent-local-*`) is carried
 *    through so the bar can offer "view conversation" once the shim has
 *    correlated the dispatch to a concrete subagent run.
 *  - letta-mobile-ww9iu/r2sh2: `subagentConversationId` is carried through
 *    when present; older shims are refreshed once on navigation and otherwise
 *    refuse navigation rather than guessing `default` or the parent.
 *
 * letta-mobile-29h9u — lingering terminals: when the shim flips a subagent to
 * a terminal status, this source stamps it with [ActiveSubagent.terminalAt] =
 * now and merges it back into the emitted snapshot. The host's
 * [withLingeringTerminals] rule then keeps it visible until its linger window
 * expires. letta-mobile-sqdqe: omission alone is not terminal; an omitted
 * RUNNING task remains visible until an explicit terminal status arrives.
 */
class WsActiveSubagentSource(
    private val repository: ISubagentRepository,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ActiveSubagentSource {

    override suspend fun todos(toolCallId: String) = repository.todos(toolCallId)

    override suspend fun resolveConversationId(subagent: ActiveSubagent): Result<String?> {
        subagent.subagentNavigationConversationId?.let { return Result.success(it) }
        return repository.refresh().map { entries ->
            entries.firstOrNull { entry ->
                entry.toolCallId == subagent.id || entry.taskId == subagent.id
            }?.subagentConversationId?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun resolveSubagent(id: String): Result<ActiveSubagent?> {
        val local = activeSubagents.value.firstOrNull { it.id == id }
        if (local?.subagentNavigationConversationId != null) return Result.success(local)
        return repository.refresh().map { entries ->
            entries.firstOrNull { entry ->
                entry.toolCallId == id || entry.taskId == id
            }?.toActiveSubagent()
        }
    }

    override val activeSubagents: StateFlow<ImmutableList<ActiveSubagent>> =
        repository.activeSubagentsFlow()
            .map { entries -> entries.map { it.toActiveSubagent() } }
            .scan(LingerAccumulator()) { acc, snapshot -> acc.fold(snapshot, clock()) }
            .map { it.emitted }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = persistentListOf(),
            )

    /**
     * letta-mobile-29h9u: immutable diff accumulator that stamps freshly
     * terminal chips with a [ActiveSubagent.terminalAt] and merges lingering
     * terminals into each emitted snapshot. Kept pure (folded over the flow,
     * `now` injected) so the linger semantics are unit-testable.
     */
    internal data class LingerAccumulator(
        /** Last known running entries, retained across incomplete snapshots. */
        private val running: Map<String, ActiveSubagent> = emptyMap(),
        /** Terminal entries currently lingering, keyed by id, stamped. */
        private val lingering: Map<String, ActiveSubagent> = emptyMap(),
        /** The list the source emits for this fold step. */
        val emitted: ImmutableList<ActiveSubagent> = persistentListOf(),
    ) {
        fun fold(snapshot: List<ActiveSubagent>, now: Long): LingerAccumulator {
            val byId = snapshot.associateBy { it.id }
            val nextRunning = running.toMutableMap()
            val nextLingering = lingering.toMutableMap()

            for (entry in snapshot) {
                if (entry.isActive) {
                    nextRunning[entry.id] = entry
                    nextLingering.remove(entry.id)
                } else if (entry.isTerminal) {
                    nextRunning.remove(entry.id)
                    nextLingering[entry.id] = nextLingering[entry.id]
                        ?: entry.copy(terminalAt = now)
                }
            }

            val expired = nextLingering.filterValues { lingered ->
                val stampedAt = lingered.terminalAt
                stampedAt == null ||
                    now - stampedAt >= ActiveSubagent.TERMINAL_LINGER_MS ||
                    byId[lingered.id]?.isActive == true
            }.keys
            expired.forEach { nextLingering.remove(it) }

            val snapshotActiveIds = snapshot.filter { it.isActive }.map { it.id }.toSet()
            val retainedRunning = nextRunning.values.filter { it.id !in snapshotActiveIds }
            val terminalsEmitted = nextLingering.values.filter { it.id !in nextRunning }
            val merged = (snapshot.filter { it.isActive } + retainedRunning + terminalsEmitted).toImmutableList()

            return LingerAccumulator(
                running = nextRunning,
                lingering = nextLingering,
                emitted = merged,
            )
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        internal fun SubagentEntry.toActiveSubagent(): ActiveSubagent = ActiveSubagent(
            id = toolCallId.takeIf { it.isNotBlank() }
                ?: taskId?.takeIf { it.isNotBlank() }
                ?: "subagent-${hashCode()}",
            description = description,
            subagentType = subagentType,
            status = status.toActiveSubagentStatus(),
            // letta-mobile-pvrrm: a registry entry that arrives WITHOUT a
            // tool_call_id but WITH a task_id is a background tool task
            // (run_in_background), not a dispatched subagent — render it with
            // the background-task glyph/label. FOLLOW-UP: the shim should emit
            // an explicit `kind` (and todo `progress`) per entry; until then we
            // infer the kind from the correlation keys.
            kind = if (toolCallId.isBlank() && !taskId.isNullOrBlank()) {
                ActiveSubagent.Kind.BACKGROUND_TASK
            } else {
                ActiveSubagent.Kind.SUBAGENT
            },
            subagentAgentId = subagentAgentId?.takeIf { it.isNotBlank() },
            subagentConversationId = subagentConversationId?.takeIf { it.isNotBlank() },
            // letta-mobile-i2f23: map the wire `todo_progress` snapshot
            // ({ completed, total }) into the UI-facing progress model so the
            // ring can render determinate fill. Absent / null → ring shows
            // a sliver (no todos yet).
            progress = todoProgress?.let {
                SubagentTodoProgress(completed = it.completed, total = it.total)
            },
            // letta-mobile-dvobc: baseline for the stuck heuristic. The
            // registry snapshot does not yet carry a per-todo-change
            // timestamp, so we seed it from `startedAt` when present. FOLLOW-UP
            // (shim): emit last-todo-change so "stuck" reflects real stalls,
            // not just long total runtime.
            lastUpdateAt = startedAt?.let(::parseIsoMillis),
        )

        /**
         * Best-effort ISO-8601 -> epoch-ms parse. Returns null on any
         * malformed value so a bad timestamp never crashes the bar (the chip
         * simply reads as not-stuck).
         */
        internal fun parseIsoMillis(iso: String): Long? = runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrNull()

        internal fun String.toActiveSubagentStatus(): ActiveSubagent.Status = when (this) {
            SubagentStatus.RUNNING -> ActiveSubagent.Status.RUNNING
            SubagentStatus.COMPLETED -> ActiveSubagent.Status.COMPLETED
            SubagentStatus.FAILED -> ActiveSubagent.Status.FAILED
            // letta-mobile-drv4a: `cancelled` (killed / evicted / orphaned /
            // TaskStop'd / process gone) is a TERMINAL outcome — map it to
            // FAILED so the chip renders a failed-styled outcome and lingers
            // then dismisses (29h9u), instead of being treated as still-running
            // by the forward-compat fallback below and getting stuck forever.
            SubagentStatus.CANCELLED -> ActiveSubagent.Status.FAILED
            // Forward-compat: an unrecognized status keeps the chip visible
            // (treated as still-running) rather than being filtered out.
            else -> ActiveSubagent.Status.RUNNING
        }
    }
}
