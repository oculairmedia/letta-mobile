package com.letta.mobile.data.workspace

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.composer.AutocompleteTrigger
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The `@` file suggestions for the composer's draft (letta-mobile-bzvro.26). [query] is the text
 * after the active `@`, or null when the cursor is not in a mention; [results] are the matching
 * workspace files as [Mentionable]s, ready to merge into the composer's mention list.
 */
@Immutable
data class FileMentionState(
    val query: String? = null,
    val cwd: String? = null,
    val results: List<Mentionable> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The composer's draft and the directory its `@` mentions search. */
@Immutable
data class MentionDraft(val text: String, val cwd: String?)

/** One workspace search: what was typed after `@`, and where. */
@Immutable
data class MentionSearch(val query: String, val cwd: String?)

/** Pure transitions of [FileMentionState]; a result for a query no longer typed is dropped. */
object FileMentionReducer {
    /** The search for the draft's active `@` mention, or null when the cursor is not in one. */
    fun searchFor(draft: MentionDraft): MentionSearch? =
        ComposerAutocomplete.activeToken(draft.text)
            ?.takeIf { it.trigger == AutocompleteTrigger.Mention }
            ?.let { MentionSearch(it.query, draft.cwd) }

    /** A new query: the previous results stay up until the new ones land, so the list does not blink. */
    fun queried(state: FileMentionState, search: MentionSearch?): FileMentionState =
        if (search == null) FileMentionState() else state.copy(query = search.query, cwd = search.cwd, loading = true, error = null)

    fun found(state: FileMentionState, search: MentionSearch, paths: List<String>): FileMentionState =
        if (!state.isFor(search)) state else state.copy(results = paths.map(::mentionable), loading = false)

    fun failed(state: FileMentionState, search: MentionSearch, message: String): FileMentionState =
        if (!state.isFor(search)) state else state.copy(results = emptyList(), loading = false, error = message)

    /** A workspace file as a mention: named by its file name, inserted as its path. */
    fun mentionable(path: String): Mentionable = Mentionable(
        id = "file:$path",
        label = WorkspacePaths.fileName(path),
        sublabel = WorkspacePaths.folder(path).ifEmpty { null },
        kind = MentionKind.File,
        insertText = path,
    )

    private fun FileMentionState.isFor(search: MentionSearch): Boolean = query == search.query && cwd == search.cwd
}

/**
 * Follows the composer's draft and searches the workspace for the active `@` mention: debounced,
 * the newest query superseding any search still in flight, at most [limit] results within the
 * working directory. Hosts feed it [onDraftChanged] and merge [state]'s results into the
 * composer's mentionables; the composer itself is unchanged.
 */
class FileMentionController(
    private val source: WorkspaceFileSource,
    private val scope: CoroutineScope,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
    private val limit: Int = DEFAULT_LIMIT,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(FileMentionState())
    val state: StateFlow<FileMentionState> = stateFlow.asStateFlow()

    private var searchJob: Job? = null

    fun onDraftChanged(draft: MentionDraft) {
        val search = FileMentionReducer.searchFor(draft)
        val current = stateFlow.value
        if (search?.query == current.query && draft.cwd == current.cwd) return
        searchJob?.cancel()
        stateFlow.update { FileMentionReducer.queried(it, search) }
        if (search == null) return
        searchJob = scope.launch {
            delay(debounce)
            try {
                val paths = source.search(search.query, search.cwd, limit)
                stateFlow.update { FileMentionReducer.found(it, search, paths) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? WorkspaceFileException)?.message ?: "File search failed."
                stateFlow.update { FileMentionReducer.failed(it, search, message) }
            }
        }
    }

    override fun close() {
        searchJob?.cancel()
        stateFlow.value = FileMentionState()
    }

    companion object {
        val DEFAULT_DEBOUNCE: Duration = 200.milliseconds

        /** As many as the reference client asks for. */
        const val DEFAULT_LIMIT: Int = 25
    }
}
