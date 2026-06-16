package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.SubagentDescriptor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-d9a7p (embedded half): LOCAL runtime [ActiveSubagentSource] fed
 * by [RuntimeEventPayload.SubagentStateChanged] events flowing through the
 * embedded stream-json protocol.
 *
 * Mirrors [WsActiveSubagentSource] for the LOCAL embedded runtime:
 *  - The bundle patch emits `{"type":"subagent_state", "subagents":[...]}` on
 *    every registry change (notifyListeners).
 *  - [LettaCodeStreamJsonMapper] maps it to [RuntimeEventPayload.SubagentStateChanged].
 *  - This source scans the event stream and emits the full active set as
 *    [ImmutableList<ActiveSubagent>], feeding the [ActiveSubagentBar].
 *  - Reuses the active-only + lingering-terminal rules from
 *    [ActiveSubagentSource.activeOnly] and [withLingeringTerminals].
 *
 * Wiring: [AdminChatViewModel] switches between [WsActiveSubagentSource] (remote/shim)
 * and [LocalActiveSubagentSource] (local embedded) based on the runtime type.
 * Local now shows REAL subagents instead of being suppressed to empty.
 */
class LocalActiveSubagentSource(
    runtimeEventsFlow: Flow<RuntimeEventPayload>,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ActiveSubagentSource {

    override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> =
        Result.success(emptyList())

    override suspend fun resolveConversationId(subagent: ActiveSubagent): Result<String?> =
        Result.success(subagent.subagentNavigationConversationId)

    override suspend fun resolveSubagent(id: String): Result<ActiveSubagent?> =
        Result.success(activeSubagents.value.firstOrNull { it.id == id })

    override val activeSubagents: StateFlow<ImmutableList<ActiveSubagent>> =
        runtimeEventsFlow
            .filterIsInstance<RuntimeEventPayload.SubagentStateChanged>()
            .map { event -> event.subagents.map { it.toActiveSubagent() } }
            .scan(LingerAccumulator()) { acc, snapshot -> acc.fold(snapshot, clock()) }
            .map { it.emitted }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = persistentListOf(),
            )

    /**
     * letta-mobile-d9a7p: immutable diff accumulator for lingering terminals.
     * Mirrors [WsActiveSubagentSource.LingerAccumulator] — stamps freshly
     * terminal chips with [ActiveSubagent.terminalAt] and merges lingering
     * terminals into each emitted snapshot. Pure (folded over the flow, `now`
     * injected) so the linger semantics are unit-testable.
     */
    internal data class LingerAccumulator(
        /** Last raw snapshot keyed by id, used to detect transitions. */
        private val previous: Map<String, ActiveSubagent> = emptyMap(),
        /** Terminal entries currently lingering, keyed by id, stamped. */
        private val lingering: Map<String, ActiveSubagent> = emptyMap(),
        /** The list the source emits for this fold step. */
        val emitted: ImmutableList<ActiveSubagent> = persistentListOf(),
    ) {
        fun fold(snapshot: List<ActiveSubagent>, now: Long): LingerAccumulator {
            val byId = snapshot.associateBy { it.id }
            val nextLingering = lingering.toMutableMap()

            // 1) Entries that flipped to terminal IN the snapshot.
            for (entry in snapshot) {
                if (entry.isTerminal && entry.id !in nextLingering) {
                    nextLingering[entry.id] = entry.copy(terminalAt = now)
                }
            }
            // 2) Previously-running ids that DISAPPEARED — treat as completed
            //    (the bundle dropped them on finish) so the outcome still shows.
            for ((id, prev) in previous) {
                if (id !in byId && id !in nextLingering && prev.isActive) {
                    nextLingering[id] = prev.copy(
                        status = ActiveSubagent.Status.COMPLETED,
                        terminalAt = now,
                    )
                }
            }
            // 3) Expire lingering terminals past their window; also clear one
            //    if the same id has come back RUNNING (a re-dispatch).
            val expired = nextLingering.filterValues { lingered ->
                val stampedAt = lingered.terminalAt
                stampedAt == null ||
                    now - stampedAt >= ActiveSubagent.TERMINAL_LINGER_MS ||
                    byId[lingered.id]?.isActive == true
            }.keys
            expired.forEach { nextLingering.remove(it) }

            // Emit: running entries from the snapshot + lingering terminals
            // not already represented as running. Snapshot order first, then
            // lingering terminals (stable, append-only).
            val runningEmitted = snapshot.filter { it.isActive }
            val terminalsEmitted = nextLingering.values.filter { it.id !in byId || byId[it.id]?.isActive != true }
            val merged = (runningEmitted + terminalsEmitted).toImmutableList()

            return LingerAccumulator(
                previous = byId,
                lingering = nextLingering,
                emitted = merged,
            )
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        internal fun SubagentDescriptor.toActiveSubagent(): ActiveSubagent = ActiveSubagent(
            id = toolCallId.takeIf { it.isNotBlank() } ?: subagentId.takeIf { it.isNotBlank() } ?: "subagent-${hashCode()}",
            description = description,
            subagentType = subagentType,
            status = status.toActiveSubagentStatus(),
            kind = if (toolCallId.isBlank() && isBackground) {
                ActiveSubagent.Kind.BACKGROUND_TASK
            } else {
                ActiveSubagent.Kind.SUBAGENT
            },
            subagentAgentId = agentId.takeIf { it.isNotBlank() },
            subagentConversationId = null, // Local embedded runtime does not provide conversation IDs in the registry
            progress = null, // TODO: wire progress when the bundle emits it
            lastUpdateAt = if (startTime > 0L) startTime else null,
        )

        internal fun String.toActiveSubagentStatus(): ActiveSubagent.Status = when (this.lowercase()) {
            "running", "pending" -> ActiveSubagent.Status.RUNNING
            "completed" -> ActiveSubagent.Status.COMPLETED
            "failed", "error", "cancelled" -> ActiveSubagent.Status.FAILED
            else -> ActiveSubagent.Status.RUNNING // Forward-compat: treat unknown as running
        }
    }
}
