package com.letta.mobile.data.memory.memfs

/** One agent's memory file: what an editor result is for. */
data class MemfsFileRef(val agentId: String, val path: String)

/** One agent's history view: a file's commits, or the whole repository's when [path] is null. */
data class MemfsHistoryScope(val agentId: String, val path: String?)

/** One agent's commit: what a diff result is for. */
data class MemfsCommitRef(val agentId: String, val sha: String)

/**
 * Pure transitions of [MemfsPageState] (letta-mobile-bzvro.24). The controller performs the I/O
 * and feeds results back through these; every result names the agent and file, scope or commit it
 * was for, and a result for anything no longer on screen leaves the state untouched.
 */
object MemfsPageReducer {
    /** A new agent: everything from the previous one is dropped. */
    fun withAgent(state: MemfsPageState, agentId: String?): MemfsPageState =
        if (state.agentId == agentId) state else MemfsPageState(agentId = agentId, query = state.query, tab = state.tab)

    fun listingStarted(state: MemfsPageState): MemfsPageState =
        state.copy(load = if (state.files.isEmpty()) MemfsLoad.Loading else MemfsLoad.Refreshing)

    fun listingLoaded(state: MemfsPageState, listing: MemfsAgentListing): MemfsPageState =
        if (state.agentId != listing.agentId) {
            state
        } else {
            state.copy(load = MemfsLoad.Loaded, memfsEnabled = listing.listing.enabled, files = listing.listing.files, enabling = false)
        }

    fun listingFailed(state: MemfsPageState, failure: MemfsAgentFailure): MemfsPageState =
        if (state.agentId != failure.agentId) state else state.copy(load = MemfsLoad.Failed(failure.message))

    fun withQuery(state: MemfsPageState, query: String): MemfsPageState = state.copy(query = query)

    fun withTab(state: MemfsPageState, tab: MemfsTab): MemfsPageState = state.copy(tab = tab)

    /** Whether leaving the open file now would lose unsaved edits. */
    fun guards(state: MemfsPageState, navigation: MemfsNavigation): Boolean {
        val editor = state.editor ?: return false
        if (!editor.dirty) return false
        return !(navigation is MemfsNavigation.Open && navigation.path == editor.path)
    }

    fun holding(state: MemfsPageState, navigation: MemfsNavigation): MemfsPageState =
        state.copy(pendingNavigation = navigation)

    fun released(state: MemfsPageState): MemfsPageState = state.copy(pendingNavigation = null)

    /** Opens [path] in the editor, loading; an image opens as a read-only placeholder. */
    fun opening(state: MemfsPageState, path: String): MemfsPageState {
        val isImage = state.files.firstOrNull { it.path == path }?.kind == MemfsFileKind.Image
        return state.copy(
            tab = MemfsTab.Files,
            pendingNavigation = null,
            editor = MemfsEditor(path = path, loading = !isImage, isImage = isImage),
        )
    }

    fun closed(state: MemfsPageState): MemfsPageState = state.copy(editor = null, pendingNavigation = null)

    /** The server's content for the open file: replaces both sides, clearing any conflict. */
    fun fileRead(state: MemfsPageState, file: MemfsFileRef, content: String): MemfsPageState =
        state.updateEditor(file) { it.copy(original = content, draft = content, loading = false, error = null, conflict = false) }

    fun fileFailed(state: MemfsPageState, file: MemfsFileRef, message: String): MemfsPageState =
        state.updateEditor(file) { it.copy(loading = false, saving = false, error = message) }

    /** The user's edit; ignored while the file is still loading, and for images. */
    fun edited(state: MemfsPageState, text: String): MemfsPageState {
        val editor = state.editor?.takeIf { it.editable } ?: return state
        return state.copy(editor = editor.copy(draft = text, error = null))
    }

    fun reverted(state: MemfsPageState): MemfsPageState =
        state.copy(editor = state.editor?.let { it.copy(draft = it.original, error = null, conflict = false) })

    fun saving(state: MemfsPageState): MemfsPageState =
        state.copy(editor = state.editor?.copy(saving = true, error = null))

    /** A save succeeded: what was written is now the server's content. */
    fun saved(state: MemfsPageState, file: MemfsFileRef, written: String): MemfsPageState =
        state.updateEditor(file) { it.copy(original = written, saving = false, conflict = false) }

    /**
     * The server says [update] changed files. Returns the new state and whether the open file
     * must be re-read: it is re-read when clean, and marked conflicting when it has unsaved edits.
     * A push that lands while our own save is in flight is that save's echo, not a conflict.
     */
    fun afterUpdate(state: MemfsPageState, update: MemfsUpdate): Pair<MemfsPageState, Boolean> {
        val editor = state.editor?.takeIf { it.followsServer && update.touches(it.path) } ?: return state to false
        if (editor.dirty) return state.copy(editor = editor.copy(conflict = true)) to false
        return state to true
    }

    fun keptDraft(state: MemfsPageState): MemfsPageState =
        state.copy(editor = state.editor?.copy(conflict = false))

    fun historyStarted(state: MemfsPageState, path: String?): MemfsPageState {
        val sameScope = state.history.path == path
        return state.copy(
            tab = MemfsTab.History,
            history = if (sameScope) state.history.copy(loading = true, error = null) else MemfsHistory(path = path, loading = true),
        )
    }

    fun historyLoaded(state: MemfsPageState, scope: MemfsHistoryScope, commits: List<MemfsCommit>): MemfsPageState =
        state.updateHistory(scope) { history ->
            val keepSelection = history.selectedSha?.takeIf { sha -> commits.any { it.sha == sha } }
            history.copy(
                commits = commits,
                loading = false,
                error = null,
                selectedSha = keepSelection,
                diff = if (keepSelection == null) emptyList() else history.diff,
            )
        }

    fun historyFailed(state: MemfsPageState, scope: MemfsHistoryScope, message: String): MemfsPageState =
        state.updateHistory(scope) { it.copy(loading = false, error = message) }

    fun commitSelected(state: MemfsPageState, sha: String): MemfsPageState =
        state.copy(history = state.history.copy(selectedSha = sha, diff = emptyList(), diffLoading = true, diffError = null))

    fun commitCleared(state: MemfsPageState): MemfsPageState =
        state.copy(history = state.history.copy(selectedSha = null, diff = emptyList(), diffLoading = false, diffError = null))

    fun diffLoaded(state: MemfsPageState, commit: MemfsCommitRef, diff: List<MemfsFileDiff>): MemfsPageState =
        state.updateCommit(commit) { it.copy(diff = diff, diffLoading = false) }

    fun diffFailed(state: MemfsPageState, commit: MemfsCommitRef, message: String): MemfsPageState =
        state.updateCommit(commit) { it.copy(diffLoading = false, diffError = message) }

    fun enabling(state: MemfsPageState): MemfsPageState = state.copy(enabling = true, enableError = null)

    fun enableFailed(state: MemfsPageState, failure: MemfsAgentFailure): MemfsPageState =
        if (state.agentId != failure.agentId) state else state.copy(enabling = false, enableError = failure.message)

    private fun MemfsPageState.updateEditor(file: MemfsFileRef, change: (MemfsEditor) -> MemfsEditor): MemfsPageState {
        val editor = editor
        return if (agentId != file.agentId || editor?.path != file.path) this else copy(editor = change(editor))
    }

    private fun MemfsPageState.updateHistory(scope: MemfsHistoryScope, change: (MemfsHistory) -> MemfsHistory): MemfsPageState =
        if (agentId != scope.agentId || history.path != scope.path) this else copy(history = change(history))

    private fun MemfsPageState.updateCommit(commit: MemfsCommitRef, change: (MemfsHistory) -> MemfsHistory): MemfsPageState =
        if (agentId != commit.agentId || history.selectedSha != commit.sha) this else copy(history = change(history))
}

/** A listing, with the agent it was read for. */
data class MemfsAgentListing(val agentId: String, val listing: MemfsListing)

/** A failed request, with the agent it was for and why. */
data class MemfsAgentFailure(val agentId: String, val message: String)
