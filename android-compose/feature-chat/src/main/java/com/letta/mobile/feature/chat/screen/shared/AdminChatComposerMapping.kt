package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.EffortSelection
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import kotlinx.collections.immutable.toImmutableList

/**
 * letta-mobile-bglj6.1: pure projections from [com.letta.mobile.feature.chat.screen.AdminChatViewModel]
 * state into the shared chat page's composer contract. Kept free of the ViewModel so each mapping is
 * testable on plain values.
 */
internal object AdminChatComposerMapping {

    /** What the ViewModel knows about the composer at one instant. */
    data class ComposerInputs(
        val composer: ChatComposerState,
        val canSend: Boolean,
        val canQueueWhileStreaming: Boolean,
        val maxAttachments: Int,
        val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
        val model: ChatModelUiState?,
        /** The streamed context reading; the placeholder state while there is none yet. */
        val contextUsage: ContextWindowUsageState,
    )

    /** What the ViewModel knows about the conversation's model at one instant. */
    data class ModelInputs(
        val agent: Agent?,
        val models: List<LlmModel>,
        val conversationOverride: String?,
        val effortsFor: (String?) -> List<String>,
    )

    fun composerUiState(inputs: ComposerInputs): ChatComposerUiState = ChatComposerUiState(
        text = inputs.composer.inputText,
        attachments = inputs.composer.pendingAttachments,
        error = inputs.composer.error,
        canSend = inputs.canSend,
        // The ViewModel's canSendMessages is connection-level, not payload-level: it gates input too.
        acceptsInput = inputs.canSend,
        canQueueWhileStreaming = inputs.canQueueWhileStreaming,
        maxAttachments = inputs.maxAttachments,
        attachmentLimits = inputs.attachmentLimits,
        commands = inputs.composer.slashCommands.map(ChatComposerCommand::fromSlashCommand).toImmutableList(),
        model = inputs.model,
        contextUsage = inputs.contextUsage,
        workingDirectory = null,
    )

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
     * The effort the agent was configured with. A conversation-scoped switch does not report
     * its effort back, so the agent's value is only meaningful while no override is active.
     */
    private fun currentEffort(inputs: ModelInputs): String? {
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

    fun findSlashCommand(commands: List<SlashCommand>, command: ChatComposerCommand): SlashCommand? =
        commands.firstOrNull { it.command == command.id }
}
