package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.repository.modelcontrol.ConversationModelRepository
import com.letta.mobile.data.repository.modelcontrol.ConversationModelTarget
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import javax.inject.Inject

/**
 * letta-mobile-w4q4p: the chat picker's view of the host model catalog
 * (reasoning variants per handle) and the per-conversation model switch.
 * Thin binding over the sharedLogic repositories.
 */
class ChatModelControl @Inject constructor(
    private val catalog: ModelCatalogRepository,
    private val conversationModels: ConversationModelRepository,
) {
    suspend fun refreshCatalog() {
        catalog.refresh()
    }

    fun reasoningEffortsFor(handle: String?): List<String> = catalog.reasoningEffortsFor(handle)

    /** [effort] null = provider default; absent choice = leave the effort alone. */
    suspend fun switchConversationModel(target: ConversationModelTarget, handle: String, effort: EffortSelection) {
        conversationModels.updateModel(target, handle, effort.toChoice())
    }
}

/** What the picker asked for: just a model, or a model plus an effort (null = provider default). */
sealed interface EffortSelection {
    data object Keep : EffortSelection

    data class Set(val effort: String?) : EffortSelection

    fun toChoice(): ReasoningEffortChoice = when (this) {
        Keep -> ReasoningEffortChoice.Unchanged
        is Set -> effort?.let(ReasoningEffortChoice::Named) ?: ReasoningEffortChoice.ProviderDefault
    }
}
