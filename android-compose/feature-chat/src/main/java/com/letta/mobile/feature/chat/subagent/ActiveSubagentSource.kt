package com.letta.mobile.feature.chat.subagent

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-73o2h.2: the WS SEAM.
 *
 * The bottom status bar consumes active subagents exclusively through this
 * interface. The bar (and its host) depend ONLY on this abstraction, never
 * on a concrete WS client. This is deliberately the single integration
 * point so that when the shim-side registry (letta-mobile-73o2h.1) lands,
 * wiring the real feed is a one-class change: implement
 * [ActiveSubagentSource] backed by the WS registry and bind it instead of
 * [FakeActiveSubagentSource]. Nothing in the UI changes.
 *
 * Contract:
 *  - [activeSubagents] emits the FULL current set of active subagents on
 *    every change (a snapshot, not a delta), so the UI reduces by simple
 *    replacement — no per-frame full rebuilds, matching the production
 *    reducer/render conventions and the rmzmo perf constraints.
 *  - Implementations SHOULD already filter to active-only, but the host
 *    re-applies the active-only rule defensively (see
 *    [ActiveSubagentSource.activeOnly]).
 */
interface ActiveSubagentSource {
    val activeSubagents: StateFlow<ImmutableList<ActiveSubagent>>

    /**
     * letta-mobile-73o2h.3: fetch one subagent's latest TodoWrite snapshot
     * for the tap-to-todolist sheet. Keyed by the chip id (the parent Agent
     * tool_call_id). Default returns empty so previews/fakes need no wiring;
     * the WS-backed source delegates to the repository.
     */
    suspend fun todos(toolCallId: String): Result<List<com.letta.mobile.data.model.SubagentTodo>> =
        Result.success(emptyList())

    companion object {
        /**
         * The active-only visibility rule, applied as a flow operator. Keeps
         * the rule in one place so both the fake and the (future) real WS
         * source can share it, and so the host can re-assert it defensively.
         */
        fun StateFlow<ImmutableList<ActiveSubagent>>.activeOnly(): Flow<ImmutableList<ActiveSubagent>> =
            map { list -> list.filter { it.isActive }.toImmutableList() }
    }
}

/**
 * letta-mobile-29h9u: the LINGERING-terminal visibility rule.
 *
 * Pure, testable transform over a snapshot of subagents (which may carry both
 * running and recently-terminal entries, each terminal one stamped with
 * [ActiveSubagent.terminalAt]). It relaxes the strict active-only rule so a
 * just-completed/failed chip stays reviewable for a short dwell instead of
 * vanishing instantly:
 *
 *  - Still-running chips ([ActiveSubagent.isActive]) always pass — the
 *    unchanged active-only behaviour.
 *  - Terminal chips pass only while inside their linger window, i.e. until
 *    `terminalAt + `[ActiveSubagent.TERMINAL_LINGER_MS]` <= now`. After that
 *    they are dropped (dismissed).
 *  - A terminal chip with no [ActiveSubagent.terminalAt] stamp is treated as
 *    expired and dropped — a defensive fallback so an unstamped terminal can
 *    never linger forever.
 *
 * Snapshot-by-replacement and stable ordering are preserved: the input order
 * is kept and only filtering happens, so the bar still reduces by simple
 * replacement (no per-frame rebuild — rmzmo perf constraints hold). [now] is
 * injected (wall-clock epoch-ms) so the rule is deterministic under test.
 */
fun ImmutableList<ActiveSubagent>.withLingeringTerminals(now: Long): ImmutableList<ActiveSubagent> =
    filter { entry ->
        when {
            entry.isActive -> true
            entry.isTerminal -> {
                val stampedAt = entry.terminalAt ?: return@filter false
                now - stampedAt < ActiveSubagent.TERMINAL_LINGER_MS
            }
            else -> true
        }
    }.toImmutableList()

/**
 * In-memory, fully-controllable [ActiveSubagentSource] for previews and
 * tests. Lets the bar be exercised end-to-end NOW — empty -> hidden,
 * single chip, multiple -> condensed — without the shim.
 *
 * Once letta-mobile-73o2h.1 is merged, this is replaced by a WS-backed
 * implementation; this fake stays for previews/tests.
 */
class FakeActiveSubagentSource(
    initial: List<ActiveSubagent> = emptyList(),
) : ActiveSubagentSource {
    private val _state = MutableStateFlow(initial.toImmutableList())
    override val activeSubagents: StateFlow<ImmutableList<ActiveSubagent>> = _state.asStateFlow()

    /** Replace the entire active set (snapshot semantics, see contract). */
    fun setActive(subagents: List<ActiveSubagent>) {
        _state.value = subagents.toImmutableList()
    }

    /** Add or update a single subagent by id. */
    fun upsert(subagent: ActiveSubagent) {
        val current = _state.value
        val idx = current.indexOfFirst { it.id == subagent.id }
        _state.value = if (idx >= 0) {
            current.toMutableList().also { it[idx] = subagent }.toImmutableList()
        } else {
            (current + subagent).toImmutableList()
        }
    }

    /** Transition a subagent to a terminal status (and let the host drop it). */
    fun setStatus(id: String, status: ActiveSubagent.Status) {
        val current = _state.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        _state.value = current.toMutableList()
            .also { it[idx] = it[idx].copy(status = status) }
            .toImmutableList()
    }

    fun clear() {
        _state.value = persistentListOf()
    }

    companion object {
        /** Convenience sample set for @Preview. */
        fun sample(count: Int): FakeActiveSubagentSource = FakeActiveSubagentSource(
            (1..count).map { i ->
                ActiveSubagent(
                    id = "task_$i",
                    description = sampleDescriptions[(i - 1) % sampleDescriptions.size],
                    subagentType = sampleTypes[(i - 1) % sampleTypes.size],
                    status = ActiveSubagent.Status.RUNNING,
                )
            },
        )

        private val sampleDescriptions = listOf(
            "Investigating the streaming jank regression",
            "Refactoring the chat reducer",
            "Auditing accessibility semantics",
            "Drafting the PR description",
        )
        private val sampleTypes = listOf("general", "researcher", "reviewer", "writer")
    }
}
