package com.letta.mobile.data.memory.memfs

/**
 * Pure transitions of [MemfsPageState] (letta-mobile-bzvro.24). The controller performs the I/O
 * and feeds results back through these; every result names the agent and path it was for, and a
 * result for anything no longer on screen leaves the state untouched.
 */
object MemfsPageReducer {
    /** A new agent: everything from the previous one is dropped. */
    fun withAgent(state: MemfsPageState, agentId: String?): MemfsPageState =
        if (state.agentId == agentId) state else MemfsPageState(agentId = agentId, query = state.query, tab = state.tab)

    fun listingStarted(state: MemfsPageState): MemfsPageState =
        state.copy(load = if (state.files.isEmpty()) MemfsLoad.Loading else MemfsLoad.Refreshing)

    fun listingLoaded(state: MemfsPageState, agentId: String, listing: MemfsListing): MemfsPageState =
        if (state.agentId != agentId) {
            state
        } else {
            state.copy(load = MemfsLoad.Loaded, memfsEnabled = listing.enabled, files = listing.files, enabling = false)
        }

    fun listingFailed(state: MemfsPageState, agentId: String, message: String): MemfsPageState =
        if (state.agentId != agentId) state else state.copy(load = MemfsLoad.Failed(message))

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
    fun fileRead(state: MemfsPageState, agentId: String, path: String, content: String): MemfsPageState =
        state.updateEditor(agentId, path) { it.copy(original = content, draft = content, loading = false, error = null, conflict = false) }

    fun fileFailed(state: MemfsPageState, agentId: String, path: String, message: String): MemfsPageState =
        state.updateEditor(agentId, path) { it.copy(loading = false, saving = false, error = message) }

    /** The user's edit; ignored while the file is still loading, and for images. */
    fun edited(state: MemfsPageState, text: String): MemfsPageState {
        val editor = state.editor ?: return state
        if (editor.loading || editor.isImage) return state
        return state.copy(editor = editor.copy(draft = text, error = null))
    }

    fun reverted(state: MemfsPageState): MemfsPageState =
        state.copy(editor = state.editor?.let { it.copy(draft = it.original, error = null, conflict = false) })

    fun saving(state: MemfsPageState): MemfsPageState =
        state.copy(editor = state.editor?.copy(saving = true, error = null))

    /** A save succeeded: what was written is now the server's content. */
    fun saved(state: MemfsPageState, agentId: String, path: String, written: String): MemfsPageState =
        state.updateEditor(agentId, path) { it.copy(original = written, saving = false, conflict = false) }

    /**
     * The server says [update] changed files. Returns the new state and whether the open file
     * must be re-read: it is re-read when clean, and marked conflicting when it has unsaved edits.
     * A push that lands while our own save is in flight is that save's echo, not a conflict.
     */
    fun afterUpdate(state: MemfsPageState, update: MemfsUpdate): Pair<MemfsPageState, Boolean> {
        val editor = state.editor
        if (editor == null || editor.isImage || editor.saving || !update.touches(editor.path)) return state to false
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

    fun historyLoaded(state: MemfsPageState, agentId: String, path: String?, commits: List<MemfsCommit>): MemfsPageState =
        state.updateHistory(agentId, path) { history ->
            val keepSelection = history.selectedSha?.takeIf { sha -> commits.any { it.sha == sha } }
            history.copy(
                commits = commits,
                loading = false,
                error = null,
                selectedSha = keepSelection,
                diff = if (keepSelection == null) emptyList() else history.diff,
            )
        }

    fun historyFailed(state: MemfsPageState, agentId: String, path: String?, message: String): MemfsPageState =
        state.updateHistory(agentId, path) { it.copy(loading = false, error = message) }

    fun commitSelected(state: MemfsPageState, sha: String): MemfsPageState =
        state.copy(history = state.history.copy(selectedSha = sha, diff = emptyList(), diffLoading = true, diffError = null))

    fun commitCleared(state: MemfsPageState): MemfsPageState =
        state.copy(history = state.history.copy(selectedSha = null, diff = emptyList(), diffLoading = false, diffError = null))

    fun diffLoaded(state: MemfsPageState, agentId: String, sha: String, diff: List<MemfsFileDiff>): MemfsPageState =
        state.updateCommit(agentId, sha) { it.copy(diff = diff, diffLoading = false) }

    fun diffFailed(state: MemfsPageState, agentId: String, sha: String, message: String): MemfsPageState =
        state.updateCommit(agentId, sha) { it.copy(diffLoading = false, diffError = message) }

    fun enabling(state: MemfsPageState): MemfsPageState = state.copy(enabling = true, enableError = null)

    fun enableFailed(state: MemfsPageState, agentId: String, message: String): MemfsPageState =
        if (state.agentId != agentId) state else state.copy(enabling = false, enableError = message)

    private fun MemfsPageState.updateEditor(
        agentId: String,
        path: String,
        change: (MemfsEditor) -> MemfsEditor,
    ): MemfsPageState {
        val editor = editor
        return if (this.agentId != agentId || editor?.path != path) this else copy(editor = change(editor))
    }

    private fun MemfsPageState.updateHistory(
        agentId: String,
        path: String?,
        change: (MemfsHistory) -> MemfsHistory,
    ): MemfsPageState = if (this.agentId != agentId || history.path != path) this else copy(history = change(history))

    private fun MemfsPageState.updateCommit(
        agentId: String,
        sha: String,
        change: (MemfsHistory) -> MemfsHistory,
    ): MemfsPageState = if (this.agentId != agentId || history.selectedSha != sha) this else copy(history = change(history))
}
