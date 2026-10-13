package com.letta.mobile.data.projects

import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.api.IProjectRepository
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
    val repository: IProjectRepository,
    val pins: ProjectPinStore,
    val reloads: Flow<Unit> = emptyFlow(),
)

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): the projects catalog shared by every host, over an Iroh
 * host's `project.*` relay - list, search, pin, sync, Beads remote, archive, delete, and the
 * create / edit form ([editor]). Project work items (issues) have no App Server route and are not
 * offered.
 */
class ProjectsPageController(
    private val sources: ProjectsPageSources,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable, String) -> String = { throwable, fallback -> throwable.message ?: fallback },
) {
    private val repository: IProjectRepository get() = sources.repository
    private val stateFlow = MutableStateFlow(ProjectsPageState())
    val state: StateFlow<ProjectsPageState> = stateFlow

    /** The create / edit form; a save reloads the catalog. */
    val editor = ProjectEditorController(stateFlow, repository, scope, errorMessageMapper, onSaved = { load(force = true) })

    private var loadJob: Job? = null

    fun start() {
        scope.launch { sources.pins.pinnedIds.collect { ids -> stateFlow.update { it.copy(pinnedIds = ids) } } }
        scope.launch { sources.reloads.collect { refresh() } }
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun setQuery(query: String) = stateFlow.update { it.copy(query = query) }

    /** Opens [projectId]'s detail (null closes it) and reads its Beads remote status. */
    fun select(projectId: String?) {
        stateFlow.update { it.copy(selectedId = projectId) }
        if (projectId == null) return
        scope.launch {
            runCatchingCancellable { repository.getBeadsRemoteStatus(projectId) }
                .onSuccess { status -> stateFlow.update { it.copy(beadsStatus = it.beadsStatus + (projectId to status)) } }
        }
    }

    fun togglePinned(projectId: String) {
        val pinned = projectId in stateFlow.value.pinnedIds
        stateFlow.update { it.copy(pinnedIds = if (pinned) it.pinnedIds - projectId else it.pinnedIds + projectId) }
        scope.launch { sources.pins.setPinned(projectId, !pinned) }
    }

    fun sync(projectId: String) {
        stateFlow.update { it.copy(syncingId = projectId) }
        scope.launch {
            val outcome = runCatchingCancellable { repository.triggerSync(projectId) }
            val notice = outcome.fold({ it.message ?: SYNC_TRIGGERED }, { errorMessageMapper(it, SYNC_FAILED) })
            stateFlow.update { it.copy(syncingId = null, notice = notice) }
            if (outcome.isSuccess) load(force = true)
        }
    }

    fun provisionBeadsRemote(projectId: String) {
        stateFlow.update { it.copy(provisioningId = projectId) }
        scope.launch {
            runCatchingCancellable {
                val response = repository.provisionBeadsRemote(projectId, push = true)
                response.remoteUrl ?: response.status
            }.onSuccess { notice ->
                val status = runCatchingCancellable { repository.getBeadsRemoteStatus(projectId) }.getOrNull()
                stateFlow.update { state ->
                    state.copy(
                        provisioningId = null,
                        beadsStatus = status?.let { state.beadsStatus + (projectId to it) } ?: state.beadsStatus,
                        notice = notice,
                    )
                }
            }.onFailure { e -> stateFlow.update { it.copy(provisioningId = null, notice = errorMessageMapper(e, PROVISION_FAILED)) } }
        }
    }

    fun archive(projectId: String) = mutate(projectId, ARCHIVE_FAILED) { "Archived ${repository.archiveProject(projectId).name}." }

    fun delete(projectId: String) = mutate(projectId, DELETE_FAILED) {
        val name = nameOf(projectId)
        repository.deleteProject(projectId)
        "Deleted $name."
    }

    fun dismissNotice() = stateFlow.update { it.copy(notice = null) }

    /** Runs a removal-like action on [projectId]: closes its detail, then reloads, or reopens it on failure. */
    private fun mutate(projectId: String, failure: String, action: suspend () -> String) {
        stateFlow.update { it.copy(selectedId = null) }
        scope.launch {
            runCatchingCancellable { action() }
                .onSuccess { notice ->
                    stateFlow.update { it.copy(notice = notice) }
                    load(force = true)
                }
                .onFailure { e -> stateFlow.update { it.copy(selectedId = projectId, notice = errorMessageMapper(e, failure)) } }
        }
    }

    private fun nameOf(projectId: String): String =
        stateFlow.value.projects.firstOrNull { it.identifier == projectId }?.name ?: projectId

    /** Loads the catalog ([force] skips the 60 s freshness window); a newer load supersedes one in flight. */
    private fun load(force: Boolean) {
        loadJob?.cancel()
        stateFlow.update { it.copy(loading = true) }
        loadJob = scope.launch {
            val outcome = runCatchingCancellable {
                if (force) repository.refreshProjects() else repository.refreshProjectsIfStale(maxAgeMs = FRESH_FOR_MS)
            }
            val failure = outcome.exceptionOrNull()?.let { errorMessageMapper(it, LOAD_FAILED) }
            stateFlow.update { it.loaded(repository.projects.value, failure) }
        }
    }

    private fun ProjectsPageState.loaded(fresh: List<ProjectSummary>, failure: String?): ProjectsPageState = when {
        failure == null -> copy(projects = fresh, loading = false, error = null)
        fresh.isEmpty() && projects.isEmpty() -> copy(loading = false, error = failure)
        else -> copy(projects = fresh.ifEmpty { projects }, loading = false, notice = failure)
    }

    private companion object {
        const val FRESH_FOR_MS = 60_000L
        const val LOAD_FAILED = "Failed to load projects"
        const val SYNC_TRIGGERED = "Sync triggered"
        const val SYNC_FAILED = "Failed to trigger sync"
        const val PROVISION_FAILED = "Failed to provision Beads remote"
        const val ARCHIVE_FAILED = "Failed to archive project"
        const val DELETE_FAILED = "Failed to delete project"
    }
}

/**
 * The Projects page's create / edit form over the page state: a new project (name, path, git URL)
 * or a project's settings (path and git URL). It closes on a save and stays open, with the failure
 * as the page notice, when the host refuses.
 */
class ProjectEditorController internal constructor(
    private val stateFlow: MutableStateFlow<ProjectsPageState>,
    private val repository: IProjectRepository,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable, String) -> String,
    private val onSaved: () -> Unit,
) {
    fun startCreate() = stateFlow.update { it.copy(editor = ProjectEditor()) }

    fun startEdit(projectId: String) {
        val project = stateFlow.value.projects.firstOrNull { it.identifier == projectId } ?: return
        stateFlow.update { it.copy(editor = ProjectEditor(draft = ProjectDraft.of(project), identifier = projectId)) }
    }

    fun updateDraft(draft: ProjectDraft) = stateFlow.update { state -> state.copy(editor = state.editor?.copy(draft = draft)) }

    fun close() = stateFlow.update { it.copy(editor = null) }

    fun submit() {
        val editor = stateFlow.value.editor ?: return
        val draft = editor.draft.normalized()
        if (editor.submitting || !draft.isReady(editor.creating)) return
        stateFlow.update { it.copy(editor = editor.copy(draft = draft, submitting = true)) }
        scope.launch {
            runCatchingCancellable { save(editor.identifier, draft) }
                .onSuccess { saved ->
                    val verb = if (editor.creating) "Created" else "Saved"
                    stateFlow.update { it.copy(editor = null, notice = "$verb ${saved.name}.") }
                    onSaved()
                }
                .onFailure { e ->
                    val notice = errorMessageMapper(e, if (editor.creating) CREATE_FAILED else UPDATE_FAILED)
                    stateFlow.update { it.copy(editor = it.editor?.copy(submitting = false), notice = notice) }
                }
        }
    }

    private suspend fun save(identifier: String?, draft: ProjectDraft): ProjectSummary {
        val gitUrl = draft.gitUrl.takeIf { it.isNotEmpty() }
        return if (identifier == null) {
            repository.createProject(name = draft.name, filesystemPath = draft.filesystemPath, gitUrl = gitUrl)
        } else {
            repository.updateProject(identifier = identifier, filesystemPath = draft.filesystemPath, gitUrl = gitUrl)
        }
    }

    private companion object {
        const val CREATE_FAILED = "Failed to create project"
        const val UPDATE_FAILED = "Failed to update project settings"
    }
}
