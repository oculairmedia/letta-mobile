package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.GoalStatus
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Goal status fetch, push-driven refresh, and goal command execution.
 */
internal class AdminChatGoalCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val slashCommandRepository: ISlashCommandRepository,
    private val wsChatBridge: WsChatBridge,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val bannerController: ChatBannerController,
    private val isShimBackend: StateFlow<Boolean>,
    private val localRuntimeRouting: () -> LocalRuntimeRouting,
    private val onGoalSlashCommandsDetected: () -> Unit,
) {
    private var goalStatusJob: Job? = null

    fun startObserving() {
        scope.launch {
            wsChatBridge.events.collect { event ->
                if (event is WsTimelineEvent.GoalsUpdated) refreshGoalStatus()
            }
        }
        scope.launch {
            isShimBackend
                .filter { it }
                .distinctUntilChanged()
                .collect { refreshGoalStatus() }
        }
        scope.launch {
            uiState
                .map { (it.conversationState as? ConversationState.Ready)?.conversationId }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    goalStatusJob?.cancel()
                    goalStatusJob = null
                    uiState.update { state ->
                        state.copy(goalStatus = null, isGoalStatusLoading = false)
                    }
                    refreshGoalStatus()
                }
        }
    }

    fun refreshGoalStatus() {
        goalStatusJob?.cancel()
        if (localRuntimeRouting() == LocalRuntimeRouting.LocalBound) {
            goalStatusJob = null
            uiState.update { it.copy(goalStatus = null, isGoalStatusLoading = false) }
            return
        }
        val originatingConversationId = currentConversationId()
        goalStatusJob = scope.launch {
            uiState.update { it.copy(isGoalStatusLoading = true) }
            slashCommandRepository.getGoalStatus(agentId.value)
                .onSuccess { status ->
                    if (currentConversationId() != originatingConversationId) return@onSuccess
                    uiState.update {
                        it.copy(goalStatus = status.goal?.toUi(), isGoalStatusLoading = false)
                    }
                }
                .onFailure {
                    if (currentConversationId() == originatingConversationId) {
                        uiState.update { it.copy(isGoalStatusLoading = false) }
                    }
                }
        }
    }

    private fun currentConversationId(): String? =
        (uiState.value.conversationState as? ConversationState.Ready)?.conversationId

    fun sendGoalCommand(command: String) {
        if (!isShimBackend.value) return
        scope.launch {
            slashCommandRepository.executeGoalCommand(agentId.value, command)
                .onSuccess { message ->
                    bannerController.showComposerError("Goal: $message")
                    refreshGoalStatus()
                }
                .onFailure { error ->
                    bannerController.showComposerError(error.message ?: "Goal command failed")
                }
        }
    }

    fun continueGoal(sendMessage: (String) -> Unit) {
        val objective = uiState.value.goalStatus?.objective?.takeIf { it.isNotBlank() } ?: return
        sendMessage(
            "Continue working on the active goal: $objective. Take the next concrete step, " +
                "and update or complete the goal when appropriate.",
        )
    }

    fun notifyGoalSlashCommandsLoaded(commands: List<com.letta.mobile.data.model.SlashCommand>) {
        if (commands.any { it.source == "builtin_goal" || it.command.startsWith("/goal") }) {
            onGoalSlashCommandsDetected()
        }
    }

    private fun GoalStatus.toUi() = GoalStatusUi(
        objective = objective,
        status = status,
        activeTimeSeconds = activeTimeSeconds,
        tokensUsed = tokensUsed,
        tokenBudget = tokenBudget,
    )
}
