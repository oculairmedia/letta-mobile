package com.letta.mobile.data.projects

import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Projects controller reads and writes, and what makes it reload (a backend switch). */
class ProjectsPageSources(
    val backend: ProjectsBackend,
    val pins: ProjectPinStore,
    val reloads: Flow<Unit> = emptyFlow(),
)

/** What the page tells the user when an action fails and the host gave no reason. */
enum class ProjectFailure(val fallback: String) {
    Load("Failed to load projects"),
    Create("Failed to create project"),
    Update("Failed to update project settings"),
    Sync("Failed to trigger sync"),
    Provision("Failed to provision Beads remote"),
    Archive("Failed to archive project"),
    Delete("Failed to delete project"),
}

/** Turns a failure into the page's words: the host's reason, or [ProjectFailure.fallback]. */
fun interface ProjectFailureWords {
    fun describe(error: Throwable, failure: ProjectFailure): String

    companion object {
        /** Wraps a host's error mapper (Android's mapErrorToUserMessage). */
        fun of(mapper: (Throwable, String) -> String) = ProjectFailureWords { error, failure -> mapper(error, failure.fallback) }

        val Default = ProjectFailureWords { error, failure -> error.message ?: failure.fallback }
    }
}

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): the projects catalog shared by every host, over an Iroh
 * host's `project.*` relay - list, search, pin, sync, Beads remote, archive, delete, and the
 * create / edit form ([editor]). Project work items (issues) have no App Server route and are not
 * offered.
 */
class ProjectsPageController(
    private val sources: ProjectsPageSources,
    private val scope: CoroutineScope,
    private val words: ProjectFailureWords = ProjectFailureWords.Default,
) {
    private val backend: ProjectsBackend get() = sources.backend
    private val stateFlow = MutableStateFlow(ProjectsPageState())
    val state: StateFlow<ProjectsPageState> = stateFlow

    /** The create / edit form; a save reloads the catalog. */
    val editor = ProjectEditorController(ProjectEditorBinding(stateFlow, backend, scope, words), onSaved = { load(ProjectReload.Force) })

    private var loadJob: Job? = null

    fun start() {
        scope.launch { sources.pins.pinned.collect { keys -> stateFlow.update { it.copy(pinned = keys) } } }
        scope.launch { sources.reloads.collect { refresh() } }
        load(ProjectReload.IfStale)
    }

    fun refresh() = load(ProjectReload.Force)

    fun setQuery(query: String) = stateFlow.update { it.copy(query = query) }

    /** Opens [key]'s detail (null closes it) and reads its Beads remote status. */
    fun select(key: ProjectKey?) {
        stateFlow.update { it.copy(selectedKey = key) }
        if (key == null) return
        scope.launch {
            runCatchingCancellable { backend.beadsStatus(key) }
                .onSuccess { status -> stateFlow.update { it.copy(beadsStatus = it.beadsStatus + (key to status)) } }
        }
    }

    fun togglePinned(key: ProjectKey) {
        val pin = if (key in stateFlow.value.pinned) ProjectPin.Unpinned else ProjectPin.Pinned
        stateFlow.update { it.copy(pinned = if (pin == ProjectPin.Pinned) it.pinned + key else it.pinned - key) }
        scope.launch { sources.pins.set(key, pin) }
    }

    fun sync(key: ProjectKey) {
        stateFlow.update { it.copy(syncing = key) }
        scope.launch {
            val outcome = runCatchingCancellable { backend.sync(key) }
            val notice = outcome.fold({ it.message ?: SYNC_TRIGGERED }, { words.describe(it, ProjectFailure.Sync) })
            stateFlow.update { it.copy(syncing = null, notice = notice) }
            if (outcome.isSuccess) load(ProjectReload.Force)
        }
    }

    fun provisionBeadsRemote(key: ProjectKey) {
        stateFlow.update { it.copy(provisioning = key) }
        scope.launch {
            runCatchingCancellable { backend.provisionBeads(key).let { it.remoteUrl ?: it.status } }
                .onSuccess { notice -> stateFlow.update { it.copy(provisioning = null, notice = notice) } }
                .onFailure { e -> stateFlow.update { it.copy(provisioning = null, notice = words.describe(e, ProjectFailure.Provision)) } }
            runCatchingCancellable { backend.beadsStatus(key) }
                .onSuccess { status -> stateFlow.update { it.copy(beadsStatus = it.beadsStatus + (key to status)) } }
        }
    }

    fun archive(key: ProjectKey) = mutate(key, ProjectFailure.Archive) { "Archived ${backend.archive(key).name}." }

    fun delete(key: ProjectKey) = mutate(key, ProjectFailure.Delete) {
        val name = stateFlow.value.projectOf(key)?.name ?: key.value
        backend.delete(key)
        "Deleted $name."
    }

    fun dismissNotice() = stateFlow.update { it.copy(notice = null) }

    /** Runs a removal-like action on [key]: closes its detail, then reloads, or reopens it on failure. */
    private fun mutate(key: ProjectKey, failure: ProjectFailure, action: suspend () -> String) {
        stateFlow.update { it.copy(selectedKey = null) }
        scope.launch {
            runCatchingCancellable { action() }
                .onSuccess { notice ->
                    stateFlow.update { it.copy(notice = notice) }
                    load(ProjectReload.Force)
                }
                .onFailure { e -> stateFlow.update { it.copy(selectedKey = key, notice = words.describe(e, failure)) } }
        }
    }

    /** Loads the catalog; a newer load supersedes one in flight. */
    private fun load(mode: ProjectReload) {
        loadJob?.cancel()
        stateFlow.update { it.copy(loading = true) }
        loadJob = scope.launch {
            val failure = runCatchingCancellable { backend.reload(mode) }.exceptionOrNull()?.let { words.describe(it, ProjectFailure.Load) }
            stateFlow.update { it.loaded(backend.projects.value, failure) }
        }
    }

    private fun ProjectsPageState.loaded(fresh: List<ProjectSummary>, failure: String?): ProjectsPageState = when {
        failure == null -> copy(projects = fresh, loading = false, error = null)
        fresh.isEmpty() && projects.isEmpty() -> copy(loading = false, error = failure)
        else -> copy(projects = fresh.ifEmpty { projects }, loading = false, notice = failure)
    }

    private companion object {
        const val SYNC_TRIGGERED = "Sync triggered"
    }
}

/** What the form controller shares with the page controller. */
class ProjectEditorBinding internal constructor(
    internal val state: MutableStateFlow<ProjectsPageState>,
    internal val backend: ProjectsBackend,
    internal val scope: CoroutineScope,
    internal val words: ProjectFailureWords,
)

/**
 * The Projects page's create / edit form over the page state: a new project (name, path, git URL)
 * or a project's settings (path and git URL). It closes on a save and stays open, with the failure
 * as the page notice, when the host refuses.
 */
class ProjectEditorController internal constructor(
    private val binding: ProjectEditorBinding,
    private val onSaved: () -> Unit,
) {
    private val stateFlow: MutableStateFlow<ProjectsPageState> get() = binding.state

    fun startCreate() = stateFlow.update { it.copy(editor = ProjectEditor()) }

    fun startEdit(key: ProjectKey) {
        val project = stateFlow.value.projectOf(key) ?: return
        stateFlow.update { it.copy(editor = ProjectEditor(draft = ProjectDraft.of(project), target = key)) }
    }

    fun updateDraft(draft: ProjectDraft) = stateFlow.update { state -> state.copy(editor = state.editor?.copy(draft = draft)) }

    fun close() = stateFlow.update { it.copy(editor = null) }

    fun submit() {
        val editor = stateFlow.value.editor ?: return
        val draft = editor.draft.normalized()
        if (editor.submitting || !draft.isReady(editor.creating)) return
        stateFlow.update { it.copy(editor = editor.copy(draft = draft, submitting = true)) }
        binding.scope.launch {
            runCatchingCancellable { save(editor.copy(draft = draft)) }
                .onSuccess { saved ->
                    val verb = if (editor.creating) "Created" else "Saved"
                    stateFlow.update { it.copy(editor = null, notice = "$verb ${saved.name}.") }
                    onSaved()
                }
                .onFailure { e ->
                    val notice = binding.words.describe(e, if (editor.creating) ProjectFailure.Create else ProjectFailure.Update)
                    stateFlow.update { it.copy(editor = it.editor?.copy(submitting = false), notice = notice) }
                }
        }
    }

    private suspend fun save(editor: ProjectEditor): ProjectSummary {
        val target = editor.target
        return if (target == null) binding.backend.create(editor.draft) else binding.backend.update(target, editor.draft)
    }
}
