package com.letta.mobile.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * letta-mobile-cygd: backing model for [CreateProjectScreen].
 *
 * The 5-step conversational creation flow used to live as a
 * `MultiFieldInputDialog` inside `ProjectHomeScreen`, with state owned by
 * [ProjectHomeViewModel]. Lifting the flow into its own full-screen route
 * means it gets its own VM too — keeps the picker-list VM lean and lets
 * each step's input survive configuration changes without polluting
 * `ProjectHomeUiState`.
 *
 * Success path: create the project, then emit
 * [CreateProjectEvent.ProjectCreated] so the screen can pop the back
 * stack and signal `ProjectHomeScreen` to refresh.
 */
@androidx.compose.runtime.Immutable
data class CreateProjectUiState(
    val draft: ConversationalProjectDraft = ConversationalProjectDraft(),
    val step: ConversationalProjectStep = ConversationalProjectStep.Goal,
    val isSubmitting: Boolean = false,
)

sealed interface CreateProjectEvent {
    data class ProjectCreated(val name: String, val hadGoal: Boolean) : CreateProjectEvent
    data class CreationFailed(val message: String) : CreateProjectEvent
}

@HiltViewModel
class CreateProjectViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState.asStateFlow()

    private val _events = Channel<CreateProjectEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun updateDraft(draft: ConversationalProjectDraft) {
        _uiState.update { it.copy(draft = draft) }
    }

    /**
     * Step backwards. Returns true if we consumed the input (i.e. moved
     * to a previous step); false if we're at the first step and the
     * caller should pop the navigation back stack instead.
     */
    fun goBack(): Boolean {
        val current = _uiState.value
        if (current.step == ConversationalProjectStep.Goal) return false
        _uiState.update {
            it.copy(
                step = it.step.previous(),
                isSubmitting = false,
            )
        }
        return true
    }

    /**
     * Step forward, or — if we're on Review with a valid draft —
     * actually submit the create call. Idempotent if already submitting.
     */
    fun advanceOrSubmit() {
        val current = _uiState.value
        if (current.isSubmitting) return
        val normalized = current.draft.normalized()

        if (current.step != ConversationalProjectStep.Review) {
            if (!normalized.isReadyFor(current.step)) {
                // Surface trimmed text but block advancement.
                _uiState.update { it.copy(draft = normalized) }
                return
            }
            _uiState.update {
                it.copy(
                    draft = normalized,
                    step = it.step.next(),
                )
            }
            return
        }

        if (!normalized.isReadyToCreate()) {
            _uiState.update { it.copy(draft = normalized) }
            return
        }

        _uiState.update {
            it.copy(
                draft = normalized,
                isSubmitting = true,
            )
        }
        viewModelScope.launch {
            runCatching {
                projectRepository.createProject(
                    name = normalized.name.takeIf { it.isNotBlank() },
                    filesystemPath = normalized.filesystemPath,
                    gitUrl = normalized.gitUrl.takeIf { it.isNotBlank() },
                )
            }.onSuccess { created ->
                _events.trySend(
                    CreateProjectEvent.ProjectCreated(
                        name = created.name,
                        hadGoal = normalized.goal.isNotBlank(),
                    )
                )
            }.onFailure { error ->
                _uiState.update { it.copy(isSubmitting = false) }
                _events.trySend(
                    CreateProjectEvent.CreationFailed(
                        message = error.message ?: "Failed to create project",
                    )
                )
            }
        }
    }
}
