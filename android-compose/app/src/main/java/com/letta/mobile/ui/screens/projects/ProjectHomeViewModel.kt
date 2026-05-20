package com.letta.mobile.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.PmAgentMetadata
import com.letta.mobile.data.api.ProjectAgentApi
import com.letta.mobile.data.repository.ProjectRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class NewProjectDraft(
    val name: String = "",
    val description: String = "",
    val filesystemPath: String = "",
    val gitUrl: String = "",
) {
    fun isReadyToSubmit(): Boolean =
        name.trim().isNotBlank() && filesystemPath.trim().isNotBlank()

    fun normalized(): NewProjectDraft = copy(
        name = name.trim(),
        description = description.trim(),
        filesystemPath = filesystemPath.trim(),
        gitUrl = gitUrl.trim(),
    )
}

@androidx.compose.runtime.Immutable
data class ConversationalProjectDraft(
    val goal: String = "",
    val name: String = "",
    val filesystemPath: String = "",
    val gitUrl: String = "",
) {
    enum class FilesystemPathValidation {
        Missing,
        MustBeAbsolute,
        Valid,
    }

    fun normalized(): ConversationalProjectDraft = copy(
        goal = goal.trim(),
        name = name.trim(),
        filesystemPath = filesystemPath.trim(),
        gitUrl = gitUrl.trim(),
    )

    fun filesystemPathValidation(): FilesystemPathValidation = when {
        filesystemPath.trim().isBlank() -> FilesystemPathValidation.Missing
        !filesystemPath.trim().startsWith("/") -> FilesystemPathValidation.MustBeAbsolute
        else -> FilesystemPathValidation.Valid
    }

    fun isReadyFor(step: ConversationalProjectStep): Boolean = when (step) {
        ConversationalProjectStep.Goal -> goal.trim().isNotBlank()
        ConversationalProjectStep.Name -> name.trim().isNotBlank()
        ConversationalProjectStep.FilesystemPath -> filesystemPathValidation() == FilesystemPathValidation.Valid
        ConversationalProjectStep.GitUrl -> true
        ConversationalProjectStep.Review -> isReadyToCreate()
    }

    fun isReadyToCreate(): Boolean =
        name.trim().isNotBlank() && filesystemPathValidation() == FilesystemPathValidation.Valid
}

enum class ConversationalProjectStep {
    Goal,
    Name,
    FilesystemPath,
    GitUrl,
    Review,
}

@androidx.compose.runtime.Immutable
data class ProjectSettingsDraft(
    val identifier: String = "",
    val projectName: String = "",
    val filesystemPath: String = "",
    val gitUrl: String = "",
) {
    enum class FilesystemPathValidation {
        Missing,
        MustBeAbsolute,
        Valid,
    }

    fun normalized(): ProjectSettingsDraft = copy(
        filesystemPath = filesystemPath.trim(),
        gitUrl = gitUrl.trim(),
    )

    fun filesystemPathValidation(): FilesystemPathValidation = when {
        filesystemPath.trim().isBlank() -> FilesystemPathValidation.Missing
        !filesystemPath.trim().startsWith("/") -> FilesystemPathValidation.MustBeAbsolute
        else -> FilesystemPathValidation.Valid
    }

    fun isReadyToSubmit(): Boolean = filesystemPathValidation() == FilesystemPathValidation.Valid
}

internal enum class ProjectSettingsPathGuidance {
    Missing,
    MustBeAbsolute,
    KnownProjectPath,
    UnknownServerAccess,
}

internal fun knownProjectPathSuggestions(projects: List<ProjectSummary>): List<String> {
    return projects
        .mapNotNull { it.filesystemPath?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .sorted()
}

internal fun projectSettingsPathGuidance(
    draft: ProjectSettingsDraft,
    knownProjectPaths: Set<String>,
): ProjectSettingsPathGuidance = when (draft.filesystemPathValidation()) {
    ProjectSettingsDraft.FilesystemPathValidation.Missing -> ProjectSettingsPathGuidance.Missing
    ProjectSettingsDraft.FilesystemPathValidation.MustBeAbsolute -> ProjectSettingsPathGuidance.MustBeAbsolute
    ProjectSettingsDraft.FilesystemPathValidation.Valid -> {
        if (draft.filesystemPath.trim() in knownProjectPaths) {
            ProjectSettingsPathGuidance.KnownProjectPath
        } else {
            ProjectSettingsPathGuidance.UnknownServerAccess
        }
    }
}

sealed interface ProjectHomeUiEvent {
    data class ShowMessage(val message: String) : ProjectHomeUiEvent
}

@androidx.compose.runtime.Immutable
data class ProjectHomeUiState(
    val projects: ImmutableList<ProjectSummary> = persistentListOf(),
    val searchQuery: String = "",
    val selectedProjectId: String? = null,
    val isRefreshing: Boolean = false,
    val showCreateOptions: Boolean = false,
    val showManualCreateDialog: Boolean = false,
    val newProjectDraft: NewProjectDraft = NewProjectDraft(),
    val showProjectSettingsDialog: Boolean = false,
    val projectSettingsDraft: ProjectSettingsDraft = ProjectSettingsDraft(),
    val showArchiveProjectDialog: Boolean = false,
    val showDeleteProjectDialog: Boolean = false,
    val showProvisionBeadsRemoteDialog: Boolean = false,
    val isSubmittingManualCreate: Boolean = false,
    val isSubmittingProjectSettings: Boolean = false,
    val isProvisioningBeadsRemote: Boolean = false,
    val syncingProjectId: String? = null,
    val beadsRemoteStatusByProject: Map<String, BeadsRemoteStatus> = emptyMap(),
    val pmAgentByProject: Map<String, PmAgentMetadata> = emptyMap(),
    val pinnedProjectIds: Set<String> = emptySet(),
)

@HiltViewModel
class ProjectHomeViewModel private constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: ISettingsRepository,
    private val projectAgentApi: ProjectAgentApi? = null,
    private val vibesyncEventStreamRepository: VibesyncEventStreamRepository? = null,
    private val externalScope: CoroutineScope? = null,
) : ViewModel() {

    @Inject
    constructor(
        projectRepository: ProjectRepository,
        settingsRepository: ISettingsRepository,
        projectAgentApi: ProjectAgentApi? = null,
        vibesyncEventStreamRepository: VibesyncEventStreamRepository? = null,
    ) : this(
        projectRepository = projectRepository,
        settingsRepository = settingsRepository,
        projectAgentApi = projectAgentApi,
        vibesyncEventStreamRepository = vibesyncEventStreamRepository,
        externalScope = null,
    )

    internal constructor(
        projectRepository: ProjectRepository,
        settingsRepository: ISettingsRepository,
        coroutineScope: CoroutineScope,
    ) : this(
        projectRepository = projectRepository,
        settingsRepository = settingsRepository,
        projectAgentApi = null,
        vibesyncEventStreamRepository = null,
        externalScope = coroutineScope,
    )

    private val _uiState = MutableStateFlow<UiState<ProjectHomeUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ProjectHomeUiState>> = _uiState.asStateFlow()
    private val _events = Channel<ProjectHomeUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var latestPinnedProjectIds: Set<String> = emptySet()
    private val launchScope: CoroutineScope
        get() = externalScope ?: viewModelScope

    init {
        observePinnedProjects()
        observeVibesyncEvents()
        loadProjects()
        // letta-mobile-ze5l: refetch projects on backend switch.
        launchScope.launch {
            settingsRepository.activeConfigChanges.collect { refresh() }
        }
    }

    private fun observePinnedProjects() {
        launchScope.launch {
            settingsRepository.getPinnedProjectIds().collect { pinnedProjectIds ->
                applyPinnedProjects(pinnedProjectIds)
            }
        }
    }

    fun loadProjects(forceRefresh: Boolean = false) {
        launchScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            if (current == null) {
                _uiState.value = UiState.Loading
            } else {
                _uiState.value = UiState.Success(current.copy(isRefreshing = true))
            }

            val refreshed = runCatching {
                if (forceRefresh) {
                    projectRepository.refreshProjects()
                } else {
                    projectRepository.refreshProjectsIfStale(maxAgeMs = 60_000)
                }
            }

            if (refreshed.isFailure) {
                val error = refreshed.exceptionOrNull()
                android.util.Log.w(
                    "ProjectHomeVM",
                    "Failed to load projects",
                    error,
                )

                if (current == null && projectRepository.projects.value.isEmpty()) {
                    _uiState.value = UiState.Error(error?.message ?: "Failed to load projects")
                    return@launch
                }

                _events.trySend(ProjectHomeUiEvent.ShowMessage(error?.message ?: "Failed to refresh projects"))
            }

            val projects = sortProjects(projectRepository.projects.value, latestPinnedProjectIds)

            _uiState.value = UiState.Success(
                ProjectHomeUiState(
                    projects = projects.toImmutableList(),
                    pinnedProjectIds = latestPinnedProjectIds,
                    searchQuery = current?.searchQuery ?: "",
                    selectedProjectId = current?.selectedProjectId,
                    showCreateOptions = current?.showCreateOptions ?: false,
                    showManualCreateDialog = current?.showManualCreateDialog ?: false,
                    newProjectDraft = current?.newProjectDraft ?: NewProjectDraft(),
                    showProjectSettingsDialog = current?.showProjectSettingsDialog ?: false,
                    projectSettingsDraft = current?.projectSettingsDraft ?: ProjectSettingsDraft(),
                    showArchiveProjectDialog = current?.showArchiveProjectDialog ?: false,
                    showDeleteProjectDialog = current?.showDeleteProjectDialog ?: false,
                    isSubmittingManualCreate = false,
                    isSubmittingProjectSettings = false,
                    isRefreshing = false,
                )
            )
        }
    }

    fun refresh() {
        loadProjects(forceRefresh = true)
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun selectProject(projectId: String?) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = projectId,
                showManualCreateDialog = if (projectId == null) current.showManualCreateDialog else false,
                newProjectDraft = if (projectId == null) current.newProjectDraft else NewProjectDraft(),
                showProjectSettingsDialog = if (projectId == null) current.showProjectSettingsDialog else false,
                projectSettingsDraft = if (projectId == null) current.projectSettingsDraft else ProjectSettingsDraft(),
                showArchiveProjectDialog = if (projectId == null) current.showArchiveProjectDialog else false,
                showDeleteProjectDialog = if (projectId == null) current.showDeleteProjectDialog else false,
            )
        )
        if (projectId != null) loadSelectedProjectMetadata(projectId)
    }

    private fun loadSelectedProjectMetadata(projectId: String) {
        val project = projectRepository.projects.value.firstOrNull { it.identifier == projectId }
            ?: (_uiState.value as? UiState.Success)?.data?.projects?.firstOrNull { it.identifier == projectId }
            ?: return
        launchScope.launch {
            runCatching { projectRepository.getBeadsRemoteStatus(project.identifier) }
                .onSuccess { status -> updateSuccess { it.copy(beadsRemoteStatusByProject = it.beadsRemoteStatusByProject + (project.identifier to status)) } }
                .onFailure { android.util.Log.i("ProjectHomeVM", "Beads remote status unavailable", it) }
        }
        launchScope.launch {
            val agentApi = projectAgentApi ?: return@launch
            runCatching { agentApi.lookup(project.name) }
                .onSuccess { agent -> updateSuccess { current ->
                    if (agent == null) current.copy(pmAgentByProject = current.pmAgentByProject - project.identifier)
                    else current.copy(pmAgentByProject = current.pmAgentByProject + (project.identifier to agent))
                } }
                .onFailure { error -> android.util.Log.i("ProjectHomeVM", "PM agent lookup unavailable", error) }
        }
    }

    fun startProvisionBeadsRemote() = updateSuccess { it.copy(showProvisionBeadsRemoteDialog = true) }

    fun dismissProvisionBeadsRemote() = updateSuccess { it.copy(showProvisionBeadsRemoteDialog = false) }

    fun confirmProvisionBeadsRemote() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val project = current.projects.firstOrNull { it.identifier == current.selectedProjectId } ?: return
        _uiState.value = UiState.Success(current.copy(showProvisionBeadsRemoteDialog = false, isProvisioningBeadsRemote = true))
        launchScope.launch {
            runCatching { projectRepository.provisionBeadsRemote(project.identifier, push = true) }
                .onSuccess { response ->
                    runCatching { projectRepository.getBeadsRemoteStatus(project.identifier) }
                        .onSuccess { status -> updateSuccess { it.copy(beadsRemoteStatusByProject = it.beadsRemoteStatusByProject + (project.identifier to status)) } }
                    _events.trySend(ProjectHomeUiEvent.ShowMessage(response.remoteUrl ?: response.status))
                }
                .onFailure { error -> _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to provision Beads remote")) }
            updateSuccess { it.copy(isProvisioningBeadsRemote = false) }
        }
    }

    fun triggerSyncNow() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val project = current.projects.firstOrNull { it.identifier == current.selectedProjectId } ?: return
        _uiState.value = UiState.Success(current.copy(syncingProjectId = project.identifier))
        launchScope.launch {
            runCatching { projectRepository.triggerSync(project.identifier) }
                .onSuccess {
                    _events.trySend(ProjectHomeUiEvent.ShowMessage(it.message ?: "Sync triggered"))
                    // Fallback: when no SSE stream is wired the sync:completed event will
                    // never arrive, so refresh and clear the syncing flag here.
                    if (vibesyncEventStreamRepository == null) {
                        loadProjects(forceRefresh = true)
                        updateSuccess { state -> state.copy(syncingProjectId = null) }
                    }
                }
                .onFailure { error ->
                    updateSuccess { it.copy(syncingProjectId = null) }
                    _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to trigger sync"))
                }
        }
    }

    private fun observeVibesyncEvents() {
        val eventRepository = vibesyncEventStreamRepository ?: return
        eventRepository.start()
        launchScope.launch {
            eventRepository.events.collect { event ->
                val projectId = event.projectId ?: return@collect
                when (event.type) {
                    "sync:started", "sync:triggered" -> updateSuccess { it.copy(syncingProjectId = projectId) }
                    "sync:completed", "sync:error" -> {
                        updateSuccess { it.copy(syncingProjectId = if (it.syncingProjectId == projectId) null else it.syncingProjectId) }
                        if (event.type == "sync:completed") loadProjects(forceRefresh = true)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        vibesyncEventStreamRepository?.stop()
        super.onCleared()
    }

    private inline fun updateSuccess(transform: (ProjectHomeUiState) -> ProjectHomeUiState) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(transform(current))
    }

    fun toggleSelectedProjectPinned() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val project = current.projects.firstOrNull { it.identifier == current.selectedProjectId } ?: return
        val isPinned = project.identifier in current.pinnedProjectIds
        val nextPinnedProjectIds = if (isPinned) {
            current.pinnedProjectIds - project.identifier
        } else {
            current.pinnedProjectIds + project.identifier
        }
        latestPinnedProjectIds = nextPinnedProjectIds
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = null,
                pinnedProjectIds = nextPinnedProjectIds,
                projects = sortProjects(
                    projects = projectRepository.projects.value.ifEmpty { current.projects },
                    pinnedProjectIds = nextPinnedProjectIds,
                ).toImmutableList(),
            )
        )
        launchScope.launch {
            settingsRepository.setProjectPinned(project.identifier, !isPinned)
            _events.trySend(
                ProjectHomeUiEvent.ShowMessage(
                    if (isPinned) "Unpinned ${project.name}." else "Pinned ${project.name}."
                )
            )
        }
    }

    fun showCreateProjectOptions() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = null,
                showCreateOptions = true,
            )
        )
    }

    fun dismissCreateProjectOptions() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(showCreateOptions = false))
    }

    fun startManualProjectCreation() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = null,
                showManualCreateDialog = true,
            )
        )
    }

    fun dismissManualProjectCreation() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.copy(
                showManualCreateDialog = false,
                newProjectDraft = NewProjectDraft(),
            )
        )
    }

    fun updateNewProjectDraft(draft: NewProjectDraft) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(newProjectDraft = draft))
    }

    // letta-mobile-mpr4: conversational creation state machine moved
    // to CreateProjectViewModel (full-screen route). The
    // ConversationalProjectDraft / ConversationalProjectStep types and
    // their next()/previous() extensions remain in this file because
    // CreateProjectViewModel still imports them from the same package.

    fun submitManualProjectCreation() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val draft = current.newProjectDraft.normalized()
        if (!draft.isReadyToSubmit()) {
            _uiState.value = UiState.Success(current.copy(newProjectDraft = draft))
            return
        }
        _uiState.value = UiState.Success(
            current.copy(
                newProjectDraft = draft,
                isSubmittingManualCreate = true,
            )
        )
        launchScope.launch {
            runCatching {
                projectRepository.createProject(
                    name = draft.name.takeIf { it.isNotBlank() },
                    filesystemPath = draft.filesystemPath,
                    gitUrl = draft.gitUrl.takeIf { it.isNotBlank() },
                )
            }.onSuccess { created ->
                loadProjects(forceRefresh = true)
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onSuccess
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        showManualCreateDialog = false,
                        newProjectDraft = NewProjectDraft(),
                        isSubmittingManualCreate = false,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage("Created ${created.name}."))
            }.onFailure { error ->
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onFailure
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        newProjectDraft = draft,
                        isSubmittingManualCreate = false,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to create project"))
            }
        }
    }

    fun startProjectSettingsEdit() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val project = current.projects.firstOrNull { it.identifier == current.selectedProjectId } ?: return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = null,
                showProjectSettingsDialog = true,
                projectSettingsDraft = ProjectSettingsDraft(
                    identifier = project.identifier,
                    projectName = project.name,
                    filesystemPath = project.filesystemPath.orEmpty(),
                    gitUrl = project.gitUrl.orEmpty(),
                ),
            )
        )
    }

    fun dismissProjectSettingsEdit() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.copy(
                showProjectSettingsDialog = false,
                projectSettingsDraft = ProjectSettingsDraft(),
            )
        )
    }

    fun updateProjectSettingsDraft(draft: ProjectSettingsDraft) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(projectSettingsDraft = draft))
    }

    fun submitProjectSettingsEdit() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val draft = current.projectSettingsDraft.normalized()
        if (!draft.isReadyToSubmit()) {
            _uiState.value = UiState.Success(current.copy(projectSettingsDraft = draft))
            return
        }
        _uiState.value = UiState.Success(
            current.copy(
                projectSettingsDraft = draft,
                isSubmittingProjectSettings = true,
            )
        )
        launchScope.launch {
            runCatching {
                projectRepository.updateProject(
                    identifier = draft.identifier,
                    filesystemPath = draft.filesystemPath,
                    gitUrl = draft.gitUrl.ifBlank { null },
                )
            }.onSuccess { updated ->
                loadProjects(forceRefresh = true)
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onSuccess
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        showProjectSettingsDialog = false,
                        projectSettingsDraft = ProjectSettingsDraft(),
                        isSubmittingProjectSettings = false,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage("Saved project settings for ${updated.name}."))
            }.onFailure { error ->
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onFailure
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        projectSettingsDraft = draft,
                        isSubmittingProjectSettings = false,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to update project settings"))
            }
        }
    }

    fun startArchiveProject() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        if (current.selectedProjectId == null) return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = current.selectedProjectId,
                showArchiveProjectDialog = true,
            )
        )
    }

    fun dismissArchiveProject() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(showArchiveProjectDialog = false))
    }

    fun confirmArchiveProject() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val project = currentProject() ?: return
        _uiState.value = UiState.Success(current.copy(showArchiveProjectDialog = false, selectedProjectId = null))
        launchScope.launch {
            runCatching {
                projectRepository.archiveProject(project.identifier)
            }.onSuccess { archived ->
                loadProjects(forceRefresh = true)
                _events.trySend(ProjectHomeUiEvent.ShowMessage("Archived ${archived.name}."))
            }.onFailure { error ->
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onFailure
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        selectedProjectId = project.identifier,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to archive project"))
            }
        }
    }

    fun startDeleteProject() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        if (current.selectedProjectId == null) return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = current.selectedProjectId,
                showDeleteProjectDialog = true,
            )
        )
    }

    fun dismissDeleteProject() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(showDeleteProjectDialog = false))
    }

    fun confirmDeleteProject() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val project = currentProject() ?: return
        _uiState.value = UiState.Success(current.copy(showDeleteProjectDialog = false, selectedProjectId = null))
        launchScope.launch {
            runCatching {
                projectRepository.deleteProject(project.identifier)
            }.onSuccess {
                loadProjects(forceRefresh = true)
                _events.trySend(ProjectHomeUiEvent.ShowMessage("Deleted ${project.name}."))
            }.onFailure { error ->
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onFailure
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        selectedProjectId = project.identifier,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to delete project"))
            }
        }
    }

    fun currentProject(): ProjectSummary? {
        val current = (_uiState.value as? UiState.Success)?.data ?: return null
        return current.projects.firstOrNull { it.identifier == current.selectedProjectId }
    }

    private fun projectLastActivity(project: ProjectSummary): String {
        return project.lastActivityAt
            ?: project.updatedAt
            ?: project.lastSyncAt
            ?: project.lastCheckedAt
            ?: project.lastScanAt
            ?: ""
    }

    private fun applyPinnedProjects(pinnedProjectIds: Set<String>) {
        latestPinnedProjectIds = pinnedProjectIds
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val sourceProjects = projectRepository.projects.value.ifEmpty { current.projects }
        _uiState.value = UiState.Success(
            current.copy(
                pinnedProjectIds = pinnedProjectIds,
                projects = sortProjects(sourceProjects, pinnedProjectIds).toImmutableList(),
            )
        )
    }

    private fun sortProjects(
        projects: List<ProjectSummary>,
        pinnedProjectIds: Set<String>,
    ): List<ProjectSummary> {
        return projects.sortedWith(
            compareByDescending<ProjectSummary> { it.identifier in pinnedProjectIds }
                .thenByDescending { projectLastActivity(it) }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun ProjectHomeUiState.resetTransientProjectActions(): ProjectHomeUiState = copy(
        showCreateOptions = false,
        showManualCreateDialog = false,
        newProjectDraft = NewProjectDraft(),
        showProjectSettingsDialog = false,
        projectSettingsDraft = ProjectSettingsDraft(),
        showArchiveProjectDialog = false,
        showDeleteProjectDialog = false,
        showProvisionBeadsRemoteDialog = false,
        isSubmittingManualCreate = false,
        isSubmittingProjectSettings = false,
    )

}

internal fun ConversationalProjectStep.next(): ConversationalProjectStep = when (this) {
    ConversationalProjectStep.Goal -> ConversationalProjectStep.Name
    ConversationalProjectStep.Name -> ConversationalProjectStep.FilesystemPath
    ConversationalProjectStep.FilesystemPath -> ConversationalProjectStep.GitUrl
    ConversationalProjectStep.GitUrl -> ConversationalProjectStep.Review
    ConversationalProjectStep.Review -> ConversationalProjectStep.Review
}

internal fun ConversationalProjectStep.previous(): ConversationalProjectStep = when (this) {
    ConversationalProjectStep.Goal -> ConversationalProjectStep.Goal
    ConversationalProjectStep.Name -> ConversationalProjectStep.Goal
    ConversationalProjectStep.FilesystemPath -> ConversationalProjectStep.Name
    ConversationalProjectStep.GitUrl -> ConversationalProjectStep.FilesystemPath
    ConversationalProjectStep.Review -> ConversationalProjectStep.GitUrl
}
