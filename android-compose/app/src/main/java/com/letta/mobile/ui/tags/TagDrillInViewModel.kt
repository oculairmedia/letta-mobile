package com.letta.mobile.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.screens.templates.BUILTIN_TEMPLATES
import com.letta.mobile.ui.screens.templates.StarterAgentTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
enum class TagDrillInEntityType {
    AGENT,
    TOOL,
    TEMPLATE,
    STEP,
}

@androidx.compose.runtime.Immutable
data class TagDrillInSource(
    val entityType: TagDrillInEntityType,
    val entityId: String,
)

@androidx.compose.runtime.Immutable
data class TagDrillInItem(
    val id: String,
    val entityType: TagDrillInEntityType,
    val title: String,
    val supportingText: String? = null,
    val metadataText: String? = null,
    val otherTags: ImmutableList<String> = persistentListOf(),
)

@androidx.compose.runtime.Immutable
data class TagDrillInUiState(
    val activeTag: String? = null,
    val isLoading: Boolean = false,
    val items: ImmutableList<TagDrillInItem> = persistentListOf(),
)

@HiltViewModel
class TagDrillInViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val toolRepository: ToolRepository,
    private val stepRepository: StepRepository,
) : ViewModel() {
    private companion object {
        const val CACHE_TTL_MS = 30_000L
        const val STEP_RESULT_LIMIT = 25
    }

    private val _uiState = MutableStateFlow(TagDrillInUiState())
    val uiState: StateFlow<TagDrillInUiState> = _uiState.asStateFlow()

    fun showTag(tag: String, source: TagDrillInSource? = null) {
        val normalizedTag = tag.trim()
        if (normalizedTag.isBlank()) return

        viewModelScope.launch {
            _uiState.value = TagDrillInUiState(activeTag = normalizedTag, isLoading = true)

            runCatching { agentRepository.refreshAgentsIfStale(CACHE_TTL_MS) }
            runCatching { toolRepository.refreshToolsIfStale(CACHE_TTL_MS) }

            val agentItems = agentRepository.agents.value
                .asSequence()
                .filter { normalizedTag in it.tags }
                .map { it.toTagDrillInItem(normalizedTag) }
                .toList()

            val toolItems = toolRepository.getTools().value
                .asSequence()
                .filter { normalizedTag in it.tags }
                .map { it.toTagDrillInItem(normalizedTag) }
                .toList()

            val templateItems = BUILTIN_TEMPLATES
                .asSequence()
                .filter { normalizedTag in it.tags }
                .map { it.toTagDrillInItem(normalizedTag) }
                .toList()

            val stepItems = runCatching {
                stepRepository.listSteps(
                    StepListParams(
                        tags = listOf(normalizedTag),
                        limit = STEP_RESULT_LIMIT,
                        order = "desc",
                    )
                )
            }.getOrDefault(emptyList())
                .asSequence()
                .map { it.toTagDrillInItem(normalizedTag) }
                .toList()

            val items = (agentItems + toolItems + templateItems + stepItems)
                .filterNot { item ->
                    source?.let { it.entityType == item.entityType && it.entityId == item.id } == true
                }
                .distinctBy { "${it.entityType}:${it.id}" }
                .sortedWith(compareBy<TagDrillInItem>({ it.entityType.ordinal }, { it.title.lowercase() }))
                .toImmutableList()

            _uiState.value = TagDrillInUiState(
                activeTag = normalizedTag,
                isLoading = false,
                items = items,
            )
        }
    }

    fun dismiss() {
        _uiState.value = TagDrillInUiState()
    }
}

private fun Agent.toTagDrillInItem(activeTag: String): TagDrillInItem {
    return TagDrillInItem(
        id = id,
        entityType = TagDrillInEntityType.AGENT,
        title = name,
        supportingText = description,
        metadataText = model,
        otherTags = tags.filterNot { it == activeTag }.toImmutableList(),
    )
}

private fun Tool.toTagDrillInItem(activeTag: String): TagDrillInItem {
    return TagDrillInItem(
        id = id,
        entityType = TagDrillInEntityType.TOOL,
        title = name,
        supportingText = description,
        metadataText = listOfNotNull(toolType, sourceType).joinToString(" • ").ifBlank { null },
        otherTags = tags.filterNot { it == activeTag }.toImmutableList(),
    )
}

private fun StarterAgentTemplate.toTagDrillInItem(activeTag: String): TagDrillInItem {
    return TagDrillInItem(
        id = id,
        entityType = TagDrillInEntityType.TEMPLATE,
        title = name,
        supportingText = description,
        metadataText = icon,
        otherTags = tags.filterNot { it == activeTag }.toImmutableList(),
    )
}

private fun Step.toTagDrillInItem(activeTag: String): TagDrillInItem {
    return TagDrillInItem(
        id = id,
        entityType = TagDrillInEntityType.STEP,
        title = id,
        supportingText = listOfNotNull(status, model, providerName).joinToString(" • ").ifBlank { null },
        metadataText = runId,
        otherTags = tags.filterNot { it == activeTag }.toImmutableList(),
    )
}
