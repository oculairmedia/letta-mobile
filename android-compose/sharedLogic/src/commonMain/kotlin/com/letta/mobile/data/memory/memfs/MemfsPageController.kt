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

    private var updatesJob: Job? = null
    private var listJob: Job? = null
    private var fileJob: Job? = null
    private var historyJob: Job? = null
    private var diffJob: Job? = null

    /** Starts following server pushes. Idempotent. */
    fun start() {
        if (updatesJob != null) return
        updatesJob = scope.launch { source.updates.collect(::onUpdate) }
    }

    /** Shows [agentId]'s memory; held behind the unsaved-changes guard when the draft is dirty. */
    fun selectAgent(agentId: String?) {
        if (agentId == stateFlow.value.agentId) return
        if (agentId != null && guard(MemfsNavigation.SwitchAgent(agentId))) return
        switchAgent(agentId)
    }

    override fun refresh() {
        val agentId = stateFlow.value.agentId ?: return
        loadListing(agentId)
        if (stateFlow.value.tab == MemfsTab.History) loadHistory(agentId, stateFlow.value.history.path)
    }

    override fun updateQuery(query: String) = stateFlow.update { MemfsPageReducer.withQuery(it, query) }

    override fun selectTab(tab: MemfsTab) {
        stateFlow.update { MemfsPageReducer.withTab(it, tab) }
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        if (tab == MemfsTab.History && state.history.commits.isEmpty() && !state.history.loading) {
            loadHistory(agentId, state.history.path)
        }
    }

    override fun openFile(path: String) {
        if (guard(MemfsNavigation.Open(path))) return
        open(path)
    }

    override fun closeFile() {
        if (guard(MemfsNavigation.Close)) return
        fileJob?.cancel()
        stateFlow.update(MemfsPageReducer::closed)
    }

    override fun editDraft(text: String) = stateFlow.update { MemfsPageReducer.edited(it, text) }

    override fun save() {
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        val editor = state.editor?.takeIf { it.canSave } ?: return
        val content = editor.draft
        stateFlow.update(MemfsPageReducer::saving)
        fileJob?.cancel()
        fileJob = scope.launch {
            attempt(
                onFailure = { current, message -> MemfsPageReducer.fileFailed(current, agentId, editor.path, message) },
            ) {
                source.write(agentId, editor.path, content)
                stateFlow.update { MemfsPageReducer.saved(it, agentId, editor.path, content) }
            }
        }
    }

    override fun revertDraft() = stateFlow.update(MemfsPageReducer::reverted)

    override fun confirmDiscard() {
        val pending = stateFlow.value.pendingNavigation ?: return
        stateFlow.update { MemfsPageReducer.released(MemfsPageReducer.reverted(it)) }
        when (pending) {
            is MemfsNavigation.Open -> open(pending.path)
            MemfsNavigation.Close -> stateFlow.update(MemfsPageReducer::closed)
            is MemfsNavigation.SwitchAgent -> switchAgent(pending.agentId)
        }
    }

    override fun cancelDiscard() = stateFlow.update(MemfsPageReducer::released)

    override fun reloadFromServer() {
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        val path = state.editor?.path ?: return
        readFile(agentId, path)
    }

    override fun keepDraft() = stateFlow.update(MemfsPageReducer::keptDraft)

    override fun showHistory(path: String?) {
        val agentId = stateFlow.value.agentId ?: return
        loadHistory(agentId, path)
    }

    override fun selectCommit(sha: String) {
        val agentId = stateFlow.value.agentId ?: return
        stateFlow.update { MemfsPageReducer.commitSelected(it, sha) }
        diffJob?.cancel()
        diffJob = scope.launch {
            attempt(onFailure = { current, message -> MemfsPageReducer.diffFailed(current, agentId, sha, message) }) {
                val diff = MemfsCommitDiff.parse(source.commitDiff(agentId, sha))
                stateFlow.update { MemfsPageReducer.diffLoaded(it, agentId, sha, diff) }
            }
        }
    }

    override fun clearCommit() {
        diffJob?.cancel()
        stateFlow.update(MemfsPageReducer::commitCleared)
    }

    override fun enableMemfs() {
        val agentId = stateFlow.value.agentId ?: return
        stateFlow.update(MemfsPageReducer::enabling)
        scope.launch {
            attempt(onFailure = { current, message -> MemfsPageReducer.enableFailed(current, agentId, message) }) {
                source.enable(agentId)
                loadListing(agentId)
            }
        }
    }

    override fun close() {
        listOf(updatesJob, listJob, fileJob, historyJob, diffJob).forEach { it?.cancel() }
        updatesJob = null
    }

    /** True when [navigation] would lose unsaved edits; the step is then held for the user. */
    private fun guard(navigation: MemfsNavigation): Boolean {
        val held = MemfsPageReducer.guards(stateFlow.value, navigation)
        if (held) stateFlow.update { MemfsPageReducer.holding(it, navigation) }
        return held
    }

    private fun switchAgent(agentId: String?) {
        listOf(listJob, fileJob, historyJob, diffJob).forEach { it?.cancel() }
        stateFlow.update { MemfsPageReducer.withAgent(it, agentId) }
        agentId?.let(::loadListing)
    }

    private fun open(path: String) {
        stateFlow.update { MemfsPageReducer.opening(it, path) }
        val state = stateFlow.value
        val agentId = state.agentId ?: return
        if (state.editor?.isImage == true) return
        readFile(agentId, path)
    }

    private fun readFile(agentId: String, path: String) {
        fileJob?.cancel()
        fileJob = scope.launch {
            attempt(onFailure = { current, message -> MemfsPageReducer.fileFailed(current, agentId, path, message) }) {
                val content = source.read(agentId, path)
                stateFlow.update { MemfsPageReducer.fileRead(it, agentId, path, content) }
            }
        }
    }

    private fun loadListing(agentId: String) {
        stateFlow.update(MemfsPageReducer::listingStarted)
        listJob?.cancel()
        listJob = scope.launch {
            attempt(onFailure = { current, message -> MemfsPageReducer.listingFailed(current, agentId, message) }) {
                val listing = source.list(agentId)
                stateFlow.update { MemfsPageReducer.listingLoaded(it, agentId, listing) }
            }
        }
    }

    private fun loadHistory(agentId: String, path: String?) {
        stateFlow.update { MemfsPageReducer.historyStarted(it, path) }
        historyJob?.cancel()
        historyJob = scope.launch {
            attempt(onFailure = { current, message -> MemfsPageReducer.historyFailed(current, agentId, path, message) }) {
                val commits = source.history(agentId, path)
                stateFlow.update { MemfsPageReducer.historyLoaded(it, agentId, path, commits) }
            }
        }
    }

    private fun onUpdate(update: MemfsUpdate) {
        val agentId = stateFlow.value.agentId ?: return
        loadListing(agentId)
        val (next, reread) = MemfsPageReducer.afterUpdate(stateFlow.value, update)
        stateFlow.value = next
        next.editor?.path?.takeIf { reread }?.let { path -> readFile(agentId, path) }
        if (next.tab == MemfsTab.History) loadHistory(agentId, next.history.path)
    }

    private suspend fun attempt(
        onFailure: (MemfsPageState, String) -> MemfsPageState,
        block: suspend () -> Unit,
    ) {
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
