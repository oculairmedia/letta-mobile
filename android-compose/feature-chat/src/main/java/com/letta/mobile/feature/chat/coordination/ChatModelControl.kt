package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ConversationModelRepository
import com.letta.mobile.data.repository.modelcontrol.ConversationModelTarget
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.repository.modelcontrol.followHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Binds the shared host catalog, conversation model switch and toolset picker. */
class ChatModelControl @Inject constructor(
    private val session: com.letta.mobile.data.repository.modelcontrol.ModelControlSession,
    private val catalog: ModelCatalogRepository,
    private val conversationModels: ConversationModelRepository,
    private val providers: ProviderConnectionRepository,
) {
    fun toolsets(scope: CoroutineScope, target: ConversationModelTarget?) = session.toolsets(
        scope, target?.let { com.letta.mobile.data.toolsets.ToolsetRuntime(it.agentId, it.conversationId) },
    )

    suspend fun refreshCatalog() {
        catalog.refresh()
    }

    /** letta-mobile-w4q4p.6.1: the shared picker's view of the host catalog (the Hilt singletons, so edits show at once). */
    fun pickerSource(): ModelPickerSource = ModelPickerSource.catalog(providers, catalog)

    /** The models switched to most recently, newest first: the picker's "Recent" group. */
    val recentModels: Flow<List<String>> get() = conversationModels.recentModels

    /** The Models sheet's presenter over the same repositories. */
    fun managementController(scope: CoroutineScope): ProviderManagementController =
        ProviderManagementController(scope, providers, catalog)

    fun reasoningEffortsFor(handle: ModelHandle?): List<String> = catalog.reasoningEffortsFor(handle)

    /** letta-mobile-okvyf / dxz5s: each conversation's own model (host override or this session's pick) and window. */
    val conversationSelections: StateFlow<ConversationModelChoices>
        get() = conversationModels.selections.choices

    /** letta-mobile-dxz5s: seeds [conversationSelections] from the host's conversation records until cancelled. */
    suspend fun followHostModels(conversations: Flow<List<Conversation>>) =
        conversationModels.selections.followHost(conversations)

    suspend fun switchConversationModel(target: ConversationModelTarget, pick: ModelPick) {
        conversationModels.updateModel(target, pick.handle, pick.effort.toChoice())
    }
}

/** A picker selection: the model plus what to do with its reasoning effort. */
data class ModelPick(val handle: ModelHandle, val effort: EffortSelection = EffortSelection.Keep)

/** What the picker asked for: just a model, or a model plus an effort (null = provider default). */
sealed interface EffortSelection {
    data object Keep : EffortSelection

    data class Set(val effort: String?) : EffortSelection

    fun toChoice(): ReasoningEffortChoice = when (this) {
        Keep -> ReasoningEffortChoice.Unchanged
        is Set -> effort?.let(ReasoningEffortChoice::Named) ?: ReasoningEffortChoice.ProviderDefault
    }
}
