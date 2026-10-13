package com.letta.mobile.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.projects.ProjectFailureWords
import com.letta.mobile.data.projects.ProjectsPageController
import com.letta.mobile.data.projects.ProjectsPageSources
import com.letta.mobile.data.projects.ProjectsPageState
import com.letta.mobile.data.projects.RepositoryProjectsBackend
import com.letta.mobile.data.projects.SettingsProjectPinStore
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Android binding of the shared Projects page (letta-mobile-ckmub.2.5): the sharedLogic
 * [ProjectsPageController] over the session's project repository, pins in the settings repository,
 * reloading on a backend switch (letta-mobile-ze5l).
 */
@HiltViewModel
class ProjectHomeViewModel @Inject constructor(
    projectRepository: IProjectRepository,
    settingsRepository: ISettingsRepository,
) : ViewModel() {
    val controller = ProjectsPageController(
        sources = ProjectsPageSources(
            backend = RepositoryProjectsBackend(projectRepository),
            pins = SettingsProjectPinStore(settingsRepository),
            reloads = settingsRepository.activeConfigChanges.map { },
        ),
        scope = viewModelScope,
        words = ProjectFailureWords.of(::mapErrorToUserMessage),
    )

    val state: StateFlow<ProjectsPageState> = controller.state

    init {
        controller.start()
    }
}
