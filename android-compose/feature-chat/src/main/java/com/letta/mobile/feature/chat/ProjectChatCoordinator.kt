package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IBugReportRepository
import com.letta.mobile.util.mapErrorToUserMessage
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ProjectChatCoordinator(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val projectContext: ProjectChatContext?,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val agentRepository: IAgentRepository,
    private val blockRepository: IBlockRepository,
    private val bugReportRepository: IBugReportRepository,
    private val conversationId: () -> String?,
    private val setComposerError: (String) -> Unit,
    private val sendMessage: (String) -> Unit,
) : ChatProjectBindings {
    override fun refreshContextWindow() {
        if (agentId.isBlank()) return
        scope.launch {
            uiState.update {
                it.copy(contextWindow = it.contextWindow.copy(isLoading = true, error = null))
            }
            try {
                val overview = agentRepository.getContextWindow(AgentId(agentId), conversationId()?.let(::ConversationId))
                uiState.update {
                    it.copy(
                        contextWindow = ContextWindowUiState(
                            isLoading = false,
                            maxTokens = overview.contextWindowSizeMax,
                            currentTokens = overview.contextWindowSizeCurrent,
                            messageCount = overview.numMessages,
                            systemTokens = overview.numTokensSystem,
                            coreMemoryTokens = overview.numTokensCoreMemory,
                            externalMemoryTokens = overview.numTokensExternalMemorySummary,
                            summaryMemoryTokens = overview.numTokensSummaryMemory,
                            toolTokens = overview.numTokensFunctionsDefinitions +
                                overview.numTokensToolUsageRules +
                                overview.numTokensDirectories +
                                overview.numTokensMemoryFilesystem,
                            messageTokens = overview.numTokensMessages,
                            archivalMemoryCount = overview.numArchivalMemory,
                            recallMemoryCount = overview.numRecallMemory,
                        )
                    )
                }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        contextWindow = it.contextWindow.copy(
                            isLoading = false,
                            error = mapErrorToUserMessage(e, "Failed to load context window"),
                        )
                    )
                }
            }
        }
    }

    override fun loadProjectAgents() {
        // Agent activity loading was part of Client Mode (removed).
    }

    override fun loadRecentBugReports() {
        val projectIdentifier = projectContext?.identifier ?: return
        scope.launch {
            try {
                val recent = bugReportRepository.getRecentBugReports(projectIdentifier)
                uiState.value = uiState.value.copy(
                    bugReports = uiState.value.bugReports.copy(
                        recentReports = recent.toImmutableList(),
                        error = null,
                    )
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    bugReports = uiState.value.bugReports.copy(
                        error = mapErrorToUserMessage(e, "Failed to load recent bug reports"),
                    )
                )
            }
        }
    }

    override fun submitStructuredBugReport(draft: ProjectBugReportDraft) {
        val project = projectContext ?: return
        scope.launch {
            uiState.value = uiState.value.copy(
                bugReports = uiState.value.bugReports.copy(isSubmitting = true, error = null)
            )
            try {
                val prompt = buildBugReportPrompt(draft)
                val logged = bugReportRepository.logBugReport(
                    ProjectBugReport(
                        projectIdentifier = project.identifier,
                        title = draft.title.trim(),
                        description = draft.description.trim(),
                        severity = draft.severity.wireValue,
                        tags = draft.tags,
                        attachmentReferences = draft.attachmentReferences,
                        structuredPrompt = prompt,
                        createdAt = java.time.Instant.now().toString(),
                    )
                )
                uiState.value = uiState.value.copy(
                    bugReports = uiState.value.bugReports.copy(
                        isSubmitting = false,
                        lastSubmittedPrompt = prompt,
                        recentReports = (listOf(logged) + uiState.value.bugReports.recentReports
                            .filterNot { it.id == logged.id }
                            .take(4)).toImmutableList(),
                    )
                )
                sendMessage(prompt)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    bugReports = uiState.value.bugReports.copy(
                        isSubmitting = false,
                        error = mapErrorToUserMessage(e, "Failed to submit bug report"),
                    )
                )
            }
        }
    }

    override fun loadProjectBrief() {
        if (projectContext == null) return
        scope.launch {
            uiState.value = uiState.value.copy(
                projectBrief = uiState.value.projectBrief.copy(isLoading = true, error = null)
            )
            try {
                val blocks = blockRepository.getBlocks(agentId)
                uiState.value = uiState.value.copy(
                    projectBrief = ProjectBriefUiState(
                        isLoading = false,
                        sections = buildProjectBriefSections(blocks).toImmutableMap(),
                    )
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    projectBrief = uiState.value.projectBrief.copy(
                        isLoading = false,
                        error = mapErrorToUserMessage(e, "Failed to load project brief"),
                    )
                )
            }
        }
    }

    override fun saveProjectBriefSection(
        key: ProjectBriefSectionKey,
        content: String,
    ) {
        val existingSection = uiState.value.projectBrief.sections[key] ?: return
        scope.launch {
            uiState.value = uiState.value.copy(
                projectBrief = uiState.value.projectBrief.copy(isSaving = true, error = null)
            )
            try {
                val updatedBlock = blockRepository.updateAgentBlock(
                    agentId = agentId,
                    blockLabel = existingSection.blockLabel,
                    params = BlockUpdateParams(value = content),
                )
                val updatedSection = existingSection.copy(
                    content = updatedBlock.value,
                    updatedAt = updatedBlock.updatedAt,
                )
                uiState.value = uiState.value.copy(
                    projectBrief = uiState.value.projectBrief.copy(
                        isSaving = false,
                        sections = (uiState.value.projectBrief.sections + (key to updatedSection))
                            .toImmutableMap(),
                    )
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    projectBrief = uiState.value.projectBrief.copy(
                        isSaving = false,
                        error = mapErrorToUserMessage(e, "Failed to save project brief"),
                    )
                )
            }
        }
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)
}

private val projectBriefLabelAliases = mapOf(
    ProjectBriefSectionKey.Description to listOf(
        "project_description",
        "project-description",
        "project description",
        "description",
        "brief_description",
    ),
    ProjectBriefSectionKey.KeyDecisions to listOf(
        "key_decisions",
        "key-decisions",
        "key decisions",
        "decisions",
        "project_decisions",
    ),
    ProjectBriefSectionKey.TechStack to listOf(
        "tech_stack",
        "tech-stack",
        "tech stack",
        "stack",
        "technology_stack",
    ),
    ProjectBriefSectionKey.ActiveGoals to listOf(
        "active_goals",
        "active-goals",
        "active goals",
        "goals",
        "current_goals",
    ),
    ProjectBriefSectionKey.RecentChanges to listOf(
        "recent_changes",
        "recent-changes",
        "recent changes",
        "changes",
        "latest_changes",
    ),
)

private fun buildProjectBriefSections(blocks: List<Block>): Map<ProjectBriefSectionKey, ProjectBriefSection> {
    return ProjectBriefSectionKey.entries.mapNotNull { key ->
        val block = blocks.firstOrNull { candidate ->
            val canonical = candidate.label?.canonicalBriefLabel() ?: return@firstOrNull false
            projectBriefLabelAliases.getValue(key).any { alias ->
                canonical == alias.canonicalBriefLabel()
            }
        } ?: return@mapNotNull null

        key to ProjectBriefSection(
            key = key,
            blockLabel = block.label ?: return@mapNotNull null,
            content = block.value,
            updatedAt = block.updatedAt,
        )
    }.toMap()
}

private fun String.canonicalBriefLabel(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

private fun buildBugReportPrompt(draft: ProjectBugReportDraft): String {
    val title = draft.title.trim()
    val description = draft.description.trim()
    val tags = draft.tags.joinToString(", ").ifBlank { "none" }
    val attachments = draft.attachmentReferences.joinToString("\n") { "- $it" }
        .ifBlank { "- none" }
    return buildString {
        appendLine("Bug Report: $title")
        appendLine("Severity: ${draft.severity.wireValue}")
        appendLine("Tags: $tags")
        appendLine("Description:")
        appendLine(description)
        appendLine()
        appendLine("Attached media references:")
        appendLine(attachments)
        appendLine()
        append("Please triage this issue, decide whether to create/update beads, and route it to the appropriate coding agent if needed.")
    }.trim()
}
