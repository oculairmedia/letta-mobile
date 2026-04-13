package com.letta.mobile.ui.screens.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class StarterAgentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val systemPrompt: String,
    val tags: ImmutableList<String>,
)

@androidx.compose.runtime.Immutable
data class TemplatesUiState(
    val templates: ImmutableList<StarterAgentTemplate> = persistentListOf()
)

internal val BUILTIN_TEMPLATES = persistentListOf(
    StarterAgentTemplate(
        id = "default",
        name = "Default Agent",
        description = "A balanced assistant for everyday questions, planning, and coordination.",
        icon = "\uD83E\uDD16",
        systemPrompt = "You are a helpful general-purpose Letta assistant. Be concise, reliable, and proactive about clarifying the next useful step.",
        tags = persistentListOf("starter", "general"),
    ),
    StarterAgentTemplate(
        id = "coder",
        name = "Coding Assistant",
        description = "A starter tuned for debugging, implementation planning, and developer workflows.",
        icon = "\uD83D\uDCBB",
        systemPrompt = "You are a coding-focused Letta assistant. Prefer precise reasoning, concrete implementation steps, and code-aware troubleshooting.",
        tags = persistentListOf("starter", "coding"),
    ),
    StarterAgentTemplate(
        id = "writer",
        name = "Writing Assistant",
        description = "A starter for drafting, editing, summarizing, and shaping tone across written work.",
        icon = "\u270D\uFE0F",
        systemPrompt = "You are a writing-focused Letta assistant. Help shape drafts, improve clarity, and adapt tone to the audience while preserving intent.",
        tags = persistentListOf("starter", "writing"),
    ),
)

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<TemplatesUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<TemplatesUiState>> = _uiState.asStateFlow()

    init {
        loadTemplates()
    }

    fun loadTemplates() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(TemplatesUiState(templates = BUILTIN_TEMPLATES))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load templates")
            }
        }
    }

    fun createFromTemplate(templateId: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val template = BUILTIN_TEMPLATES.find { it.id == templateId } ?: return@launch
                val agent = agentRepository.createAgent(
                    AgentCreateParams(
                        name = template.name,
                        description = template.description,
                        system = template.systemPrompt,
                        tags = template.tags,
                        includeBaseTools = true,
                    )
                )
                onSuccess(agent.id)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to create agent")
            }
        }
    }
}
