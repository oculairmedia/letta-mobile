package com.letta.mobile.data.dreaming

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentKind
import com.letta.mobile.data.model.SubagentStatus

/** A background memory run in flight for the agent, from the subagent registry. */
@Immutable
data class DreamActivity(
    val id: String,
    val description: String,
    val kind: SubagentKind,
    /** ISO-8601 as the registry carries it, when known. */
    val startedAt: String?,
) {
    companion object {
        /** The registry's running reflection and memory-upkeep runs; every other subagent is the user's. */
        fun of(entries: List<SubagentEntry>): List<DreamActivity> = entries
            .filter { it.status == SubagentStatus.RUNNING }
            .mapNotNull { entry ->
                val kind = SubagentKind.fromWire(entry.subagentType)
                if (!kind.isBackground) return@mapNotNull null
                DreamActivity(entry.toolCallId, entry.description, kind, entry.startedAt)
            }
    }
}

/** The agent's dream log, read from its MemFS history. */
sealed interface DreamHistoryState {
    data object Idle : DreamHistoryState

    data object Loading : DreamHistoryState

    data class Loaded(val runs: List<DreamRun>) : DreamHistoryState

    data class Failed(val message: String) : DreamHistoryState
}

/** One opened dream: its diff loading, loaded, or failed. */
sealed interface DreamDetailState {
    val run: DreamRun

    data class Loading(override val run: DreamRun) : DreamDetailState

    data class Loaded(val detail: DreamRunDetail) : DreamDetailState {
        override val run: DreamRun get() = detail.run
    }

    data class Failed(override val run: DreamRun, val message: String) : DreamDetailState
}

/** The settings half of the surface: what letta-code holds, and a change in flight. */
@Immutable
data class DreamSettingsState(
    val settings: DreamSettings? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** Set once the backend said it cannot hold dreaming settings at all. */
    val unsupported: DreamUnsupportedReason? = null,
)

/** The run-now half: a start in flight, letta-code's last answer, or why it cannot start one. */
@Immutable
data class DreamRunNowState(
    val starting: Boolean = false,
    val lastStart: DreamRunStart? = null,
    val error: String? = null,
    val unsupported: DreamUnsupportedReason? = null,
)

/**
 * Everything the Dreaming surface shows for one agent. Pure data; the
 * [DreamingController] owns the I/O.
 */
@Immutable
data class DreamingState(
    val target: DreamTarget? = null,
    val settings: DreamSettingsState = DreamSettingsState(),
    val runNow: DreamRunNowState = DreamRunNowState(),
    val live: List<DreamActivity> = emptyList(),
    val history: DreamHistoryState = DreamHistoryState.Idle,
    val detail: DreamDetailState? = null,
) {
    /** A dream or memory upkeep run is in flight now. */
    val dreamingNow: Boolean get() = live.isNotEmpty()

    /** The newest dream that changed memory, when the log is loaded. */
    val lastRun: DreamRun? get() = (history as? DreamHistoryState.Loaded)?.runs?.firstOrNull()

    /** The whole surface is unavailable here: the backend cannot hold dreaming settings at all. */
    val unsupported: DreamUnsupportedReason? get() = settings.unsupported
}

/** What the Dreaming surface can ask for; [DreamingController] implements it, previews fake it. */
interface DreamingActions {
    fun refresh()

    fun save(change: DreamSettingsChange)

    fun runNow(instruction: String? = null)

    fun openRun(run: DreamRun)

    fun closeRun()
}
