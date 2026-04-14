package com.letta.mobile.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.ProjectRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class ProjectHomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ProjectHomeUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ProjectHomeUiState>> = _uiState.asStateFlow()
    private val _events = Channel<ProjectHomeUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadProjects()
    }

    fun loadProjects(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            if (current == null) {
                _uiState.value = UiState.Loading
            } else {
                _uiState.value = UiState.Success(current.copy(isRefreshing = true))
            }

            // The /api/registry/projects endpoint may not be available on all
            // server deployments; degrade gracefully to an empty list so the
            // home screen remains usable and navigation stays accessible.
            val refreshed = runCatching {
                if (forceRefresh) {
                    projectRepository.refreshProjects()
                } else {
                    projectRepository.refreshProjectsIfStale(maxAgeMs = 60_000)
                }
            }

            if (refreshed.isFailure) {
                android.util.Log.w(
                    "ProjectHomeVM",
                    "Failed to load projects",
                    refreshed.exceptionOrNull(),
                )
            }

            val projects = projectRepository.projects.value
                .sortedWith(
                    compareByDescending<ProjectSummary> { projectLastActivity(it) }
                        .thenBy { it.name.lowercase() }
                )

            _uiState.value = UiState.Success(
                ProjectHomeUiState(
                    projects = projects.toImmutableList(),
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
        return project.updatedAt
            ?: project.lastSyncAt
            ?: project.lastCheckedAt
            ?: project.lastScanAt
            ?: ""
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

    private fun ConversationalProjectStep.next(): ConversationalProjectStep = when (this) {
        ConversationalProjectStep.Goal -> ConversationalProjectStep.Name
        ConversationalProjectStep.Name -> ConversationalProjectStep.FilesystemPath
        ConversationalProjectStep.FilesystemPath -> ConversationalProjectStep.GitUrl
        ConversationalProjectStep.GitUrl -> ConversationalProjectStep.Review
        ConversationalProjectStep.Review -> ConversationalProjectStep.Review
    }

    private fun ConversationalProjectStep.previous(): ConversationalProjectStep = when (this) {
        ConversationalProjectStep.Goal -> ConversationalProjectStep.Goal
        ConversationalProjectStep.Name -> ConversationalProjectStep.Goal
        ConversationalProjectStep.FilesystemPath -> ConversationalProjectStep.Name
        ConversationalProjectStep.GitUrl -> ConversationalProjectStep.FilesystemPath
        ConversationalProjectStep.Review -> ConversationalProjectStep.GitUrl
    }
}
