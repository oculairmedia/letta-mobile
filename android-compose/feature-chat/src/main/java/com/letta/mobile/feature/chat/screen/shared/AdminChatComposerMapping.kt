package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.EffortSelection
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatHostCommand
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import kotlinx.collections.immutable.toImmutableList

/**
 * letta-mobile-bglj6.1: pure projections from [com.letta.mobile.feature.chat.screen.AdminChatViewModel]
 * state into the shared chat page's composer contract. Kept free of the ViewModel so each mapping is
 * testable on plain values.
 */
internal object AdminChatComposerMapping {

    /** What the ViewModel knows about the conversation's model at one instant. */
    data class ModelInputs(
        val agent: Agent?,
        val models: List<LlmModel>,
        val conversationOverride: String?,
        val effortsFor: (String?) -> List<String>,
        /** letta-mobile-by4vm.82: the conversation's effort (this session's pick, else the host's record). */
        val conversationEffort: String? = null,
    )

    /** The draft; the extension points' chrome ([com.letta.mobile.ui.chat.session.withChrome]) goes over it. */
    fun composerUiState(composer: ChatComposerState, canSend: Boolean, limits: AttachmentLimits) = ChatComposerUiState(
        text = composer.inputText,
        attachments = composer.pendingAttachments,
        error = composer.error,
        canSend = canSend,
        // The ViewModel's canSendMessages is connection-level, not payload-level: it gates input too.
        acceptsInput = canSend,
        maxAttachments = limits.maxAttachmentCount,
        attachmentLimits = limits,
    )

    /**
     * The server's slash commands (installed ones removable), plus the client-run /fork where the
     * page can fork, which fills the composer and is run by send.
     */
    fun hostCommands(slash: List<SlashCommand>, canFork: Boolean, select: (SlashCommand) -> Unit, uninstall: (SlashCommand) -> Unit) =
        listOfNotNull(ChatComposerCommand.Fork.takeIf { canFork }?.let { ChatHostCommand(it.label, it.description, true) {} }) +
            slash.map { command ->
                val remove = if (command.installed) ({ uninstall(command) }) else null
                ChatHostCommand(command.command, command.description, fillsComposer = true, uninstall = remove) { select(command) }
            }

    fun modelUiState(inputs: ModelInputs): ChatModelUiState? {
        val handle = ConversationModelSelections.resolve(
            conversationOverride = inputs.conversationOverride,
            agentModel = inputs.agent?.model,
        )
        if (handle == null && inputs.models.isEmpty()) return null
        val current = handle?.let { findModel(inputs.models, it) }
        return ChatModelUiState(
            currentHandle = handle,
            currentLabel = current?.displayName ?: handle.orEmpty(),
            currentEffort = currentEffort(inputs),
            options = inputs.models.map { modelOption(it, inputs.effortsFor) }.toImmutableList(),
        )
    }

    /**
     * The conversation's effort (letta-mobile-by4vm.82), else the effort the agent was configured with.
     * The agent's value only describes the conversation while no override is active.
     */
    private fun currentEffort(inputs: ModelInputs): String? {
        inputs.conversationEffort?.takeIf { it.isNotBlank() }?.let { return it }
        if (!inputs.conversationOverride.isNullOrBlank()) return null
        val agent = inputs.agent ?: return null
        return agent.llmConfig?.reasoningEffort ?: agent.modelSettings?.reasoningEffort
    }

    private fun modelOption(model: LlmModel, effortsFor: (String?) -> List<String>): ChatModelOption {
        val handle = model.handle ?: model.id
        return ChatModelOption(
            handle = handle,
            label = model.displayName,
            provider = model.providerName ?: model.providerType.ifBlank { null },
            reasoningEfforts = effortsFor(handle).toImmutableList(),
        )
    }

    private fun findModel(models: List<LlmModel>, handle: String): LlmModel? =
        models.firstOrNull { it.handle == handle || it.id == handle || handle in it.selectionAliases }

    fun effortSelection(choice: ReasoningEffortChoice): EffortSelection = when (choice) {
        ReasoningEffortChoice.Unchanged -> EffortSelection.Keep
        ReasoningEffortChoice.ProviderDefault -> EffortSelection.Set(null)
        is ReasoningEffortChoice.Named -> EffortSelection.Set(choice.effort)
    }

}
