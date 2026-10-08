package com.letta.mobile.data.memory.memfs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Platform-neutral controller behind the shared MemFS page (letta-mobile-bzvro.24): lists an
 * agent's memory files, edits one with an unsaved-changes guard, follows `memory_updated` pushes,
 * and shows commit history with diffs. Hosts bind a [MemfsSource] and choose the agent.
 */
class MemfsPageController(
    private val source: MemfsSource,
    private val scope: CoroutineScope,
) : MemfsPageActions, AutoCloseable {
    private val stateFlow = MutableStateFlow(MemfsPageState())
    val state: StateFlow<MemfsPageState> = stateFlow.asStateFlow()

    /** The I/O each part of the page waits on; a new request in a slot supersedes the old one. */
    private enum class Slot { Updates, Listing, File, History, Diff }

    private val jobs = mutableMapOf<Slot, Job>()

    /** Starts following server pushes. Idempotent. */
    fun start() {
        if (jobs[Slot.Updates]?.isActive == true) return
        jobs[Slot.Updates] = scope.launch { source.updates.collect(::onUpdate) }
    }

    /** Shows [agentId]'s memory; held behind the unsaved-changes guard when the draft is dirty. */
    fun selectAgent(agentId: String?) {
        if (agentId == stateFlow.value.agentId) return
        val target = MemfsNavigation.SwitchAgent(agentId)
        if (agentId != null && guard(target)) return
        switchAgent(target)
    }

    override fun refresh() {
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        loadListing()
        if (state.tab == MemfsTab.History) loadHistory(MemfsHistoryScope(agentId, state.history.path))
    }

    override fun updateQuery(query: String) = stateFlow.update { MemfsPageReducer.withQuery(it, query) }

    override fun selectTab(tab: MemfsTab) {
        stateFlow.update { MemfsPageReducer.withTab(it, tab) }
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        if (tab == MemfsTab.History && state.history.needsFirstLoad) loadHistory(MemfsHistoryScope(agentId, state.history.path))
    }

    override fun openFile(path: String) {
        val target = MemfsNavigation.Open(path)
        if (guard(target)) return
        open(target)
    }

    override fun closeFile() {
        if (guard(MemfsNavigation.Close)) return
        jobs[Slot.File]?.cancel()
        stateFlow.update(MemfsPageReducer::closed)
    }

    override fun editDraft(text: String) = stateFlow.update { MemfsPageReducer.edited(it, text) }

    override fun save() {
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        val editor = state.editor?.takeIf { it.canSave } ?: return
        val file = MemfsFileRef(agentId, editor.path)
        val content = editor.draft
        stateFlow.update(MemfsPageReducer::saving)
        perform(Slot.File, onFailure = { current, message -> MemfsPageReducer.fileFailed(current, file, message) }) {
            source.write(file, content)
            stateFlow.update { MemfsPageReducer.saved(it, file, content) }
        }
    }

    override fun revertDraft() = stateFlow.update(MemfsPageReducer::reverted)

    override fun confirmDiscard() {
        val pending = stateFlow.value.pendingNavigation ?: return
        stateFlow.update { MemfsPageReducer.released(MemfsPageReducer.reverted(it)) }
        when (pending) {
            is MemfsNavigation.Open -> open(pending)
            MemfsNavigation.Close -> stateFlow.update(MemfsPageReducer::closed)
            is MemfsNavigation.SwitchAgent -> switchAgent(pending)
        }
    }

    override fun cancelDiscard() = stateFlow.update(MemfsPageReducer::released)

    override fun reloadFromServer() {
        openFileRef()?.let(::readFile)
    }

    override fun keepDraft() = stateFlow.update(MemfsPageReducer::keptDraft)

    override fun showHistory(path: String?) {
        val agentId = stateFlow.value.agentId ?: return
        loadHistory(MemfsHistoryScope(agentId, path))
    }

    override fun selectCommit(sha: String) {
        val agentId = stateFlow.value.agentId ?: return
        val commit = MemfsCommitRef(agentId, sha)
        stateFlow.update { MemfsPageReducer.commitSelected(it, sha) }
        perform(Slot.Diff, onFailure = { current, message -> MemfsPageReducer.diffFailed(current, commit, message) }) {
            val diff = MemfsCommitDiff.parse(source.commitDiff(commit))
            stateFlow.update { MemfsPageReducer.diffLoaded(it, commit, diff) }
        }
    }

    override fun clearCommit() {
        jobs[Slot.Diff]?.cancel()
        stateFlow.update(MemfsPageReducer::commitCleared)
    }

    override fun enableMemfs() {
        val agentId = stateFlow.value.agentId ?: return
        stateFlow.update(MemfsPageReducer::enabling)
        scope.launch {
            attempt(onFailure = { current, message -> MemfsPageReducer.enableFailed(current, MemfsAgentFailure(agentId, message)) }) {
                source.enable(agentId)
                loadListing()
            }
        }
    }

    override fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** True when [navigation] would lose unsaved edits; the step is then held for the user. */
    private fun guard(navigation: MemfsNavigation): Boolean {
        val held = MemfsPageReducer.guards(stateFlow.value, navigation)
        if (held) stateFlow.update { MemfsPageReducer.holding(it, navigation) }
        return held
    }

    private fun switchAgent(target: MemfsNavigation.SwitchAgent) {
        jobs.filterKeys { it != Slot.Updates }.values.forEach { it.cancel() }
        stateFlow.update { MemfsPageReducer.withAgent(it, target.agentId) }
        loadListing()
    }

    private fun open(target: MemfsNavigation.Open) {
        stateFlow.update { MemfsPageReducer.opening(it, target.path) }
        if (stateFlow.value.editor?.isImage == true) return
        openFileRef()?.let(::readFile)
    }

    private fun openFileRef(): MemfsFileRef? {
        val state = stateFlow.value
        val agentId = state.agentId ?: return null
        val path = state.editor?.path ?: return null
        return MemfsFileRef(agentId, path)
    }

    private fun readFile(file: MemfsFileRef) =
        perform(Slot.File, onFailure = { current, message -> MemfsPageReducer.fileFailed(current, file, message) }) {
            val content = source.read(file)
            stateFlow.update { MemfsPageReducer.fileRead(it, file, content) }
        }

    /** Re-reads the current agent's files; nothing when no agent is shown. */
    private fun loadListing() {
        val agentId = stateFlow.value.agentId ?: return
        stateFlow.update(MemfsPageReducer::listingStarted)
        perform(Slot.Listing, onFailure = { current, message -> MemfsPageReducer.listingFailed(current, MemfsAgentFailure(agentId, message)) }) {
            val listing = source.list(agentId)
            stateFlow.update { MemfsPageReducer.listingLoaded(it, MemfsAgentListing(agentId, listing)) }
        }
    }

    private fun loadHistory(history: MemfsHistoryScope) {
        stateFlow.update { MemfsPageReducer.historyStarted(it, history.path) }
        perform(Slot.History, onFailure = { current, message -> MemfsPageReducer.historyFailed(current, history, message) }) {
            val commits = source.history(history)
            stateFlow.update { MemfsPageReducer.historyLoaded(it, history, commits) }
        }
    }

    private fun onUpdate(update: MemfsUpdate) {
        val agentId = stateFlow.value.agentId ?: return
        loadListing()
        val (next, reread) = MemfsPageReducer.afterUpdate(stateFlow.value, update)
        stateFlow.value = next
        if (reread) openFileRef()?.let(::readFile)
        if (next.tab == MemfsTab.History) loadHistory(MemfsHistoryScope(agentId, next.history.path))
    }

    /** Runs [block] in [slot], cancelling whatever the slot was waiting on. */
    private fun perform(slot: Slot, onFailure: (MemfsPageState, String) -> MemfsPageState, block: suspend () -> Unit) {
        jobs[slot]?.cancel()
        jobs[slot] = scope.launch { attempt(onFailure, block) }
    }

    private suspend fun attempt(onFailure: (MemfsPageState, String) -> MemfsPageState, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = (error as? MemfsException)?.message ?: error.message ?: "The memory request failed."
            stateFlow.update { onFailure(it, message) }
        }
    }
}
