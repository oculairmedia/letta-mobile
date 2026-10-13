package com.letta.mobile.data.templates

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A starter agent: what a new agent made from it is called, does and is told. */
@Immutable
data class StarterAgentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val systemPrompt: String,
    val tags: List<String>,
)

/** The starters every host offers; an agent made from one goes through the App Server's `agent_create`. */
val BuiltinAgentTemplates: List<StarterAgentTemplate> = listOf(
    StarterAgentTemplate(
        id = "default",
        name = "Default Agent",
        description = "A balanced assistant for everyday questions, planning, and coordination.",
        icon = "🤖",
        systemPrompt = "You are a helpful general-purpose Letta assistant. Be concise, reliable, and proactive about clarifying the next useful step.",
        tags = listOf("starter", "general"),
    ),
    StarterAgentTemplate(
        id = "coder",
        name = "Coding Assistant",
        description = "A starter tuned for debugging, implementation planning, and developer workflows.",
        icon = "💻",
        systemPrompt = "You are a coding-focused Letta assistant. Prefer precise reasoning, concrete implementation steps, and code-aware troubleshooting.",
        tags = listOf("starter", "coding"),
    ),
    StarterAgentTemplate(
        id = "writer",
        name = "Writing Assistant",
        description = "A starter for drafting, editing, summarizing, and shaping tone across written work.",
        icon = "✍️",
        systemPrompt = "You are a writing-focused Letta assistant. Help shape drafts, improve clarity, and adapt tone to the audience while preserving intent.",
        tags = listOf("starter", "writing"),
    ),
)

/** What the Templates page shows: the starters, the one being made into an agent, and a failure. */
@Immutable
data class AgentTemplatesState(
    val templates: List<StarterAgentTemplate> = BuiltinAgentTemplates,
    val creatingId: String? = null,
    val error: String? = null,
)

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): makes an agent from a starter template. The host decides
 * where a made agent opens ([create]'s callback); one creation runs at a time.
 */
class AgentTemplatesController(
    private val agentRepository: IAgentRepository,
    private val scope: CoroutineScope,
    templates: List<StarterAgentTemplate> = BuiltinAgentTemplates,
    private val errorMessageMapper: (Throwable, String) -> String = { throwable, fallback -> throwable.message ?: fallback },
) {
    private val stateFlow = MutableStateFlow(AgentTemplatesState(templates = templates))
    val state: StateFlow<AgentTemplatesState> = stateFlow

    fun create(templateId: String, onCreated: (agentId: String) -> Unit) {
        val current = stateFlow.value
        if (current.creatingId != null) return
        val template = current.templates.firstOrNull { it.id == templateId } ?: return
        stateFlow.update { it.copy(creatingId = templateId, error = null) }
        scope.launch {
            runCatchingCancellable { agentRepository.createAgent(template.toCreateParams()) }
                .onSuccess { agent ->
                    stateFlow.update { it.copy(creatingId = null) }
                    onCreated(agent.id.value)
                }
                .onFailure { e -> stateFlow.update { it.copy(creatingId = null, error = errorMessageMapper(e, CREATE_FAILED)) } }
        }
    }

    fun clearError() = stateFlow.update { it.copy(error = null) }

    private companion object {
        const val CREATE_FAILED = "Failed to create agent"
    }
}

internal fun StarterAgentTemplate.toCreateParams(): AgentCreateParams = AgentCreateParams(
    name = name,
    description = description,
    system = systemPrompt,
    tags = tags.toImmutableList(),
    includeBaseTools = true,
)
