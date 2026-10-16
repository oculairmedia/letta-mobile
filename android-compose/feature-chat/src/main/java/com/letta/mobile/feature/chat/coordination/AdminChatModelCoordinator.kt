package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.activeBackendUsesChannel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ConversationModelTarget
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Model picker and local-runtime binding updates for [AdminChatViewModel].
 */
internal class AdminChatModelCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val agentRepository: IAgentRepository,
    private val modelRepository: IModelRepository,
    private val settingsRepository: ISettingsRepository,
    private val activeAgent: StateFlow<Agent?>,
    private val bannerController: ChatBannerController,
    /** letta-mobile-w4q4p: per-conversation switch over model.update; null keeps the agent-update path. */
    private val modelControl: ChatModelControl? = null,
    private val conversation: OpenConversation = OpenConversation(),
) {
    val conversationSelections: StateFlow<ConversationModelChoices> =
        modelControl?.conversationSelections ?: MutableStateFlow(ConversationModelChoices())

    /**
     * letta-mobile-dxz5s: the agent's conversations as the host records them, so a model override
     * made outside this app (CLI, desktop, another phone) shows after a cold start. [conversations]
     * is only read when there is a model control to seed.
     */
    fun followHostModels(conversations: () -> Flow<List<Conversation>>) {
        val control = modelControl ?: return
        scope.launch { control.followHostModels(conversations()) }
    }

    fun refreshModels() {
        scope.launch {
            runCatching { modelRepository.refreshLlmModels() }
            conversationModelTarget()?.let { runCatching { modelControl?.refreshCatalog() } }
        }
    }

    fun reasoningEffortsFor(handle: ModelHandle?): List<String> = modelControl?.reasoningEffortsFor(handle).orEmpty()

    /** letta-mobile-w4q4p.6.1: the host catalog for the shared picker, on an Iroh host only (else the plain list). */
    fun pickerSource(): com.letta.mobile.data.repository.modelcontrol.ModelPickerSource? =
        modelControl?.takeIf { settingsRepository.activeBackendUsesChannel() }?.pickerSource()

    /** The Models sheet's presenter; null when the host has no exposure to edit. */
    fun modelsEditController(scope: CoroutineScope): com.letta.mobile.data.repository.modelcontrol.ProviderManagementController? =
        modelControl?.takeIf { settingsRepository.activeBackendUsesChannel() }?.managementController(scope)

    /**
     * On an Iroh host with an open conversation the pick goes through
     * `model.update` for THIS conversation (with the chosen reasoning effort);
     * otherwise it keeps updating the agent as before.
     */
    fun updateActiveAgentModel(pick: ModelPick) {
        val target = conversationModelTarget()
        val control = modelControl
        if (target != null && control != null) {
            switchConversationModel(control, target, pick)
        } else {
            updateAgentModel(pick)
        }
    }

    /** letta-mobile-3io8k: a pick goes to `model.update` on the conversation (else it updates the agent). */
    fun picksTargetConversation(): Boolean = conversationModelTarget() != null

    private fun conversationModelTarget(): ConversationModelTarget? {
        if (modelControl == null || !settingsRepository.activeBackendUsesChannel()) return null
        val openId = conversation.id()?.takeIf { it.isNotBlank() } ?: return null
        return ConversationModelTarget(agentId.value, openId)
    }

    private fun switchConversationModel(control: ChatModelControl, target: ConversationModelTarget, pick: ModelPick) {
        scope.launch {
            try {
                control.switchConversationModel(target, pick)
                runCatching { agentRepository.refreshAgents() }
                runCatching { conversation.refresh() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                bannerController.showError("Couldn't switch model: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun updateAgentModel(pick: ModelPick) {
        scope.launch {
            try {
                agentRepository.updateAgent(
                    agentId,
                    modelSwitchUpdateParams(pick.handle.value),
                )
                refreshModels()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val currentModel = activeAgent.value?.model ?: "unknown"
                bannerController.showError("Couldn't switch model — still on $currentModel")
                runCatching { agentRepository.refreshAgents() }
            }
        }
    }

    /** letta-mobile-by4vm.110.6: every backend is an App Server; the agent's model is its handle. */
    fun modelSwitchUpdateParams(handle: String): AgentUpdateParams = AgentUpdateParams(model = handle.trim())
}

/**
 * The conversation the chat has open: its id, read at each pick, and a re-read of the agent's
 * conversations after a per-conversation switch (letta-mobile-by4vm.82), so the host's record of
 * the new model / effort / window — and the cache a reopen reads — follows it.
 */
internal class OpenConversation(
    val id: () -> String? = { null },
    val refresh: suspend () -> Unit = {},
)
