package com.letta.mobile.feature.chat.coordination

import android.util.Log
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ProjectChatContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Slash-command catalog load/install/uninstall for admin chat.
 */
internal class AdminChatSlashCommandsCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val slashCommandRepository: ISlashCommandRepository,
    private val composerCoordinator: AdminChatComposerCoordinator,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val projectContext: ProjectChatContext?,
    private val localRuntimeRouting: () -> LocalRuntimeRouting,
    private val goalCoordinator: AdminChatGoalCoordinator,
) {
    private var loadVersion: Long = 0L

    fun loadSlashCommands() {
        scope.launch {
            val version = ++loadVersion
            val builtIns = buildList {
                if (projectContext != null) {
                    add(
                        SlashCommand(
                            name = "bug",
                            command = "/bug",
                            description = "Open a project bug report",
                            source = "local",
                            installed = true,
                        ),
                    )
                }
            }
            if (localRuntimeRouting() == LocalRuntimeRouting.LocalBound) {
                Log.d(TAG, "Slash commands skipped for local runtime agent=${agentId.value}")
                if (version == loadVersion) {
                    composerCoordinator.setSlashCommands(builtIns)
                    uiState.update { it.copy(goalStatus = null, isGoalStatusLoading = false) }
                }
                return@launch
            }
            val agentCommands = slashCommandRepository.listForAgent(agentId.value)
                .getOrElse { e ->
                    Log.d(TAG, "Agent slash commands unavailable: ${e.message}")
                    emptyList()
                }
            val installedNames = agentCommands.map { it.skillName ?: it.name }.toSet()
            val globalCommands = slashCommandRepository.listGlobal()
                .getOrElse { e ->
                    Log.d(TAG, "Global slash commands unavailable: ${e.message}")
                    emptyList()
                }
                .map { it.copy(installed = (it.skillName ?: it.name) in installedNames) }
            val merged = (builtIns + agentCommands + globalCommands)
                .groupBy { it.command }
                .map { (_, dupes) -> dupes.maxByOrNull { it.installed } ?: dupes.first() }
                .sortedWith(
                    compareByDescending<SlashCommand> { it.installed }
                        .thenBy { it.command },
                )
            Log.d(TAG, "Slash commands loaded: total=${merged.size} installed=${merged.count { it.installed }}")
            if (version == loadVersion) {
                composerCoordinator.setSlashCommands(merged)
                goalCoordinator.notifyGoalSlashCommandsLoaded(merged)
            }
        }
    }

    fun selectSlashCommand(command: SlashCommand) {
        composerCoordinator.insertSlashCommand(command)
        val skillName = command.skillName
        if (skillName != null && !command.installed) {
            scope.launch {
                slashCommandRepository.installToAgent(agentId.value, skillName)
                    .onSuccess {
                        Log.d(TAG, "Installed skill $skillName to ${agentId.value}")
                        loadSlashCommands()
                    }
                    .onFailure { e -> Log.d(TAG, "Skill install failed for $skillName: ${e.message}") }
            }
        }
    }

    fun uninstallSlashCommand(command: SlashCommand) {
        val skillName = command.skillName ?: return
        if (!command.installed) return
        scope.launch {
            slashCommandRepository.uninstallFromAgent(agentId.value, skillName)
                .onSuccess {
                    Log.d(TAG, "Uninstalled skill $skillName from ${agentId.value}")
                    loadSlashCommands()
                }
                .onFailure { e -> Log.d(TAG, "Skill uninstall failed for $skillName: ${e.message}") }
        }
    }

    private companion object {
        private const val TAG = "AdminChatSlashCommands"
    }
}
