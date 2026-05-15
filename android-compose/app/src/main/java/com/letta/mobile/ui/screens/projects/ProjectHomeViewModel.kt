package com.letta.mobile.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.ProjectRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
    val showConversationalCreateDialog: Boolean = false,
    val conversationalProjectDraft: ConversationalProjectDraft = ConversationalProjectDraft(),
    val conversationalProjectStep: ConversationalProjectStep = ConversationalProjectStep.Goal,
    val showProjectSettingsDialog: Boolean = false,
    val projectSettingsDraft: ProjectSettingsDraft = ProjectSettingsDraft(),
    val showArchiveProjectDialog: Boolean = false,
    val showDeleteProjectDialog: Boolean = false,
    val isSubmittingManualCreate: Boolean = false,
    val isSubmittingConversationalCreate: Boolean = false,
    val isSubmittingProjectSettings: Boolean = false,
    val pinnedProjectIds: Set<String> = emptySet(),
)

@HiltViewModel
class ProjectHomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ProjectHomeUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ProjectHomeUiState>> = _uiState.asStateFlow()
    private val _events = Channel<ProjectHomeUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var latestPinnedProjectIds: Set<String> = emptySet()

    init {
        observePinnedProjects()
        loadProjects()
        // letta-mobile-ze5l: refetch projects on backend switch.
        viewModelScope.launch {
            settingsRepository.activeConfigChanges.collect { refresh() }
        }
    }

    private fun observePinnedProjects() {
        viewModelScope.launch {
            settingsRepository.getPinnedProjectIds().collect { pinnedProjectIds ->
                applyPinnedProjects(pinnedProjectIds)
            }
        }
    }

    fun loadProjects(forceRefresh: Boolean = false) {
        viewModelScope.launch {
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
                    showConversationalCreateDialog = current?.showConversationalCreateDialog ?: false,
                    conversationalProjectDraft = current?.conversationalProjectDraft ?: ConversationalProjectDraft(),
                    conversationalProjectStep = current?.conversationalProjectStep ?: ConversationalProjectStep.Goal,
                    showProjectSettingsDialog = current?.showProjectSettingsDialog ?: false,
                    projectSettingsDraft = current?.projectSettingsDraft ?: ProjectSettingsDraft(),
                    showArchiveProjectDialog = current?.showArchiveProjectDialog ?: false,
                    showDeleteProjectDialog = current?.showDeleteProjectDialog ?: false,
                    isSubmittingManualCreate = false,
                    isSubmittingConversationalCreate = false,
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
                showConversationalCreateDialog = if (projectId == null) current.showConversationalCreateDialog else false,
                conversationalProjectDraft = if (projectId == null) current.conversationalProjectDraft else ConversationalProjectDraft(),
                conversationalProjectStep = if (projectId == null) current.conversationalProjectStep else ConversationalProjectStep.Goal,
                showProjectSettingsDialog = if (projectId == null) current.showProjectSettingsDialog else false,
                projectSettingsDraft = if (projectId == null) current.projectSettingsDraft else ProjectSettingsDraft(),
                showArchiveProjectDialog = if (projectId == null) current.showArchiveProjectDialog else false,
                showDeleteProjectDialog = if (projectId == null) current.showDeleteProjectDialog else false,
            )
        )
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
        viewModelScope.launch {
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

    fun startConversationalProjectCreation() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.resetTransientProjectActions().copy(
                selectedProjectId = null,
                showConversationalCreateDialog = true,
                conversationalProjectStep = ConversationalProjectStep.Goal,
            )
        )
    }

    fun dismissConversationalProjectCreation() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        if (!current.showConversationalCreateDialog) return
        if (current.conversationalProjectStep == ConversationalProjectStep.Goal) {
            _uiState.value = UiState.Success(
                current.copy(
                    showConversationalCreateDialog = false,
                    conversationalProjectDraft = ConversationalProjectDraft(),
                    conversationalProjectStep = ConversationalProjectStep.Goal,
                    isSubmittingConversationalCreate = false,
                )
            )
        } else {
            _uiState.value = UiState.Success(
                current.copy(
                    conversationalProjectStep = current.conversationalProjectStep.previous(),
                    isSubmittingConversationalCreate = false,
                )
            )
        }
    }

    fun updateConversationalProjectDraft(draft: ConversationalProjectDraft) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(conversationalProjectDraft = draft))
    }

    fun submitConversationalProjectCreation() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val draft = current.conversationalProjectDraft.normalized()
        val step = current.conversationalProjectStep
        if (step != ConversationalProjectStep.Review) {
            if (!draft.isReadyFor(step)) {
                _uiState.value = UiState.Success(current.copy(conversationalProjectDraft = draft))
                return
            }
            _uiState.value = UiState.Success(
                current.copy(
                    conversationalProjectDraft = draft,
                    conversationalProjectStep = step.next(),
                )
            )
            return
        }
        if (!draft.isReadyToCreate()) {
            _uiState.value = UiState.Success(current.copy(conversationalProjectDraft = draft))
            return
        }
        _uiState.value = UiState.Success(
            current.copy(
                conversationalProjectDraft = draft,
                isSubmittingConversationalCreate = true,
            )
        )
        viewModelScope.launch {
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
                        showConversationalCreateDialog = false,
                        conversationalProjectDraft = ConversationalProjectDraft(),
                        conversationalProjectStep = ConversationalProjectStep.Goal,
                        isSubmittingConversationalCreate = false,
                    )
                )
                val detail = if (draft.goal.isNotBlank()) {
                    " Project brief handoff isn't wired yet, so your setup notes stayed local."
                } else {
                    ""
                }
                _events.trySend(ProjectHomeUiEvent.ShowMessage("Created ${created.name}.$detail"))
            }.onFailure { error ->
                val refreshed = (_uiState.value as? UiState.Success)?.data ?: return@onFailure
                _uiState.value = UiState.Success(
                    refreshed.copy(
                        conversationalProjectDraft = draft,
                        isSubmittingConversationalCreate = false,
                    )
                )
                _events.trySend(ProjectHomeUiEvent.ShowMessage(error.message ?: "Failed to create project"))
            }
        }
    }

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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        showConversationalCreateDialog = false,
        conversationalProjectDraft = ConversationalProjectDraft(),
        conversationalProjectStep = ConversationalProjectStep.Goal,
        showProjectSettingsDialog = false,
        projectSettingsDraft = ProjectSettingsDraft(),
        showArchiveProjectDialog = false,
        showDeleteProjectDialog = false,
        isSubmittingManualCreate = false,
        isSubmittingConversationalCreate = false,
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
