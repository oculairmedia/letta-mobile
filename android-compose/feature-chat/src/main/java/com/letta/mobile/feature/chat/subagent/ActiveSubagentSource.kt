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
