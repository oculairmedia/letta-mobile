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

/** Pure transitions of [FileMentionState]; a result for a query no longer typed is dropped. */
object FileMentionReducer {
    /** The query of the draft's active `@` mention, or null. */
    fun mentionQuery(draft: String): String? =
        ComposerAutocomplete.activeToken(draft)?.takeIf { it.trigger == AutocompleteTrigger.Mention }?.query

    /** A new query: the previous results stay up until the new ones land, so the list does not blink. */
    fun queried(state: FileMentionState, query: String?, cwd: String?): FileMentionState =
        if (query == null) FileMentionState() else state.copy(query = query, cwd = cwd, loading = true, error = null)

    fun found(state: FileMentionState, query: String, cwd: String?, paths: List<String>): FileMentionState =
        if (!state.isFor(query, cwd)) state else state.copy(results = paths.map(::mentionable), loading = false)

    fun failed(state: FileMentionState, query: String, cwd: String?, message: String): FileMentionState =
        if (!state.isFor(query, cwd)) state else state.copy(results = emptyList(), loading = false, error = message)

    /** A workspace file as a mention: named by its file name, inserted as its path. */
    fun mentionable(path: String): Mentionable = Mentionable(
        id = "file:$path",
        label = WorkspacePaths.fileName(path),
        sublabel = WorkspacePaths.folder(path).ifEmpty { null },
        kind = MentionKind.File,
        insertText = path,
    )

    private fun FileMentionState.isFor(query: String, cwd: String?): Boolean = this.query == query && this.cwd == cwd
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

    fun onDraftChanged(draft: String, cwd: String?) {
        val query = FileMentionReducer.mentionQuery(draft)
        val current = stateFlow.value
        if (query == current.query && cwd == current.cwd) return
        searchJob?.cancel()
        stateFlow.update { FileMentionReducer.queried(it, query, cwd) }
        if (query == null) return
        searchJob = scope.launch {
            delay(debounce)
            try {
                val paths = source.search(query, cwd, limit)
                stateFlow.update { FileMentionReducer.found(it, query, cwd, paths) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? WorkspaceFileException)?.message ?: "File search failed."
                stateFlow.update { FileMentionReducer.failed(it, query, cwd, message) }
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
