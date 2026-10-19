package com.letta.mobile.data.dreaming

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one entry point the Dreaming surface uses, on every platform.
 *
 *  - [focus] picks the agent (and the conversation whose runtime reads its settings) and loads its
 *    settings and dream log; focusing another agent supersedes every request for the previous one.
 *  - [save] applies a settings change optimistically and keeps letta-code's stored answer.
 *  - [runNow] asks letta-code to dream; its answer is the launch, the run itself shows up in
 *    [DreamingState.live] (the subagent registry, via [activity]) and, when it changes memory,
 *    in the log, which reloads when the last live run ends.
 *  - [openRun] loads one dream's diff.
 *
 * [history] is null where the session has no MemFS route; the log then stays [DreamHistoryState.Idle].
 */
class DreamingController(
    private val repository: DreamingRepository,
    private val history: DreamHistorySource?,
    private val scope: CoroutineScope,
    private val activity: (AgentId) -> Flow<List<SubagentEntry>> = { emptyFlow() },
) : DreamingActions, AutoCloseable {
    private val stateFlow = MutableStateFlow(DreamingState())
    val state: StateFlow<DreamingState> = stateFlow.asStateFlow()

    /** The I/O each part waits on; a new request in a slot supersedes the old one. */
    private enum class Slot { Settings, Save, Run, History, Detail, Activity }

    private val jobs = mutableMapOf<Slot, Job>()

    fun focus(target: DreamTarget) {
        if (stateFlow.value.target == target) return
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        stateFlow.value = DreamingState(target = target)
        refresh()
        follow(target)
    }

    /** Re-reads the settings and the dream log of the focused agent. */
    override fun refresh() {
        val target = stateFlow.value.target ?: return
        loadSettings(target)
        loadHistory(target)
    }

    override fun save(change: DreamSettingsChange) {
        val target = stateFlow.value.target ?: return
        val previous = stateFlow.value.settings.settings
        updateSettings { it.copy(settings = change.settings, saving = true, error = null) }
        launch(Slot.Save, target) {
            val outcome = repository.save(target, change)
            applyIfCurrent(target) { state -> state.copy(settings = state.settings.afterSave(outcome, previous)) }
        }
    }

    override fun runNow(instruction: String?) {
        val target = stateFlow.value.target ?: return
        if (stateFlow.value.runNow.starting) return
        stateFlow.update { it.copy(runNow = it.runNow.copy(starting = true, error = null)) }
        launch(Slot.Run, target) {
            val outcome = repository.runNow(target, instruction)
            applyIfCurrent(target) { state -> state.copy(runNow = state.runNow.after(outcome)) }
        }
    }

    override fun openRun(run: DreamRun) {
        val target = stateFlow.value.target ?: return
        val source = history ?: return
        stateFlow.update { it.copy(detail = DreamDetailState.Loading(run)) }
        launch(Slot.Detail, target) {
            val detail = runCatchingCancellable { source.detail(target.agentId.value, run) }.fold(
                onSuccess = { DreamDetailState.Loaded(it) },
                onFailure = { DreamDetailState.Failed(run, it.message ?: DIFF_FAILED) },
            )
            applyIfCurrent(target) { state -> state.copy(detail = detail.takeIf { state.detail?.run == run }) }
        }
    }

    override fun closeRun() {
        jobs.remove(Slot.Detail)?.cancel()
        stateFlow.update { it.copy(detail = null) }
    }

    override fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun loadSettings(target: DreamTarget) {
        updateSettings { it.copy(loading = true, error = null) }
        launch(Slot.Settings, target) {
            val outcome = repository.settings(target)
            applyIfCurrent(target) { state -> state.copy(settings = state.settings.afterLoad(outcome)) }
        }
    }

    private fun loadHistory(target: DreamTarget) {
        val source = history ?: return
        stateFlow.update { it.copy(history = DreamHistoryState.Loading) }
        launch(Slot.History, target) {
            val loaded = runCatchingCancellable { source.runs(target.agentId.value) }.fold(
                onSuccess = { DreamHistoryState.Loaded(it) },
                onFailure = { DreamHistoryState.Failed(it.message ?: HISTORY_FAILED) },
            )
            applyIfCurrent(target) { it.copy(history = loaded) }
        }
    }

    /** Follows the agent's live background runs; the log reloads when the last one ends. */
    private fun follow(target: DreamTarget) {
        launch(Slot.Activity, target) {
            activity(target.agentId).map(DreamActivity::of).distinctUntilChanged().collect { live ->
                val ended = stateFlow.value.dreamingNow && live.isEmpty()
                applyIfCurrent(target) { it.copy(live = live) }
                if (ended) loadHistory(target)
            }
        }
    }

    private fun updateSettings(change: (DreamSettingsState) -> DreamSettingsState) =
        stateFlow.update { it.copy(settings = change(it.settings)) }

    private fun applyIfCurrent(target: DreamTarget, change: (DreamingState) -> DreamingState) =
        stateFlow.update { if (it.target == target) change(it) else it }

    private fun launch(slot: Slot, target: DreamTarget, block: suspend () -> Unit) {
        jobs.remove(slot)?.cancel()
        val job = scope.launch { if (stateFlow.value.target == target) block() }
        jobs[slot] = job
        job.invokeOnCompletion { if (jobs[slot] === job) jobs.remove(slot) }
    }

    private companion object {
        const val HISTORY_FAILED = "Couldn't read the memory history"
        const val DIFF_FAILED = "Couldn't read this dream's changes"
    }
}

/** The settings after a load: letta-code's stored settings, why it cannot hold them, or its error. */
internal fun DreamSettingsState.afterLoad(outcome: DreamOutcome<DreamSettings>): DreamSettingsState = when (outcome) {
    is DreamOutcome.Done -> copy(settings = outcome.value, loading = false, error = null, unsupported = null)
    is DreamOutcome.Unsupported -> copy(loading = false, unsupported = outcome.reason)
    is DreamOutcome.Failed -> copy(loading = false, error = outcome.message)
}

/** The settings after a save: the stored answer, or [previous] back with the error. */
internal fun DreamSettingsState.afterSave(outcome: DreamOutcome<DreamSettings>, previous: DreamSettings?): DreamSettingsState = when (outcome) {
    is DreamOutcome.Done -> copy(settings = outcome.value, saving = false, error = null)
    is DreamOutcome.Unsupported -> copy(settings = previous, saving = false, unsupported = outcome.reason)
    is DreamOutcome.Failed -> copy(settings = previous, saving = false, error = outcome.message)
}

/** Run-now after letta-code answered. */
internal fun DreamRunNowState.after(outcome: DreamOutcome<DreamRunStart>): DreamRunNowState = when (outcome) {
    is DreamOutcome.Done -> copy(starting = false, lastStart = outcome.value, error = null)
    is DreamOutcome.Unsupported -> copy(starting = false, unsupported = outcome.reason)
    is DreamOutcome.Failed -> copy(starting = false, error = outcome.message)
}
