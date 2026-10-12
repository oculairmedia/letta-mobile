package com.letta.mobile.data.context

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections

/**
 * letta-mobile-dxz5s: what the context card and composer show for one conversation, resolved once
 * for Android and desktop alike.
 *
 * [model] is the conversation's own model (host override or this session's pick), else the
 * agent's. [windowTokens] is the meter's window: the conversation's host window, else that model's
 * catalog window, else the agent's (see [windowTokensFor]). [modelWindowTokens] is the model's
 * catalog window, the limit slider's ceiling. [effort] is the host's recorded effort for the
 * conversation's model, else the catalog row's.
 */
data class ConversationModelView(
    val model: String?,
    /** The catalog's display name for [model], else [model] itself. */
    val label: String?,
    val windowTokens: Int?,
    val modelWindowTokens: Int?,
    val effort: String?,
) {
    companion object {
        fun of(choices: ConversationModelChoices, conversationId: String?, agent: Agent?, models: List<LlmModel>): ConversationModelView {
            val model = ConversationModelSelections.resolve(conversationId?.let(choices.models::get), agent?.model)
            val catalogRow = models.firstOrNull { it.handle == model || it.id == model }
            return ConversationModelView(
                model = model,
                label = catalogRow?.displayName?.takeIf { it.isNotBlank() } ?: model,
                windowTokens = choices.windowTokensFor(conversationId, agent, models),
                modelWindowTokens = modelCatalogWindowOf(models, model),
                effort = conversationId?.let(choices.efforts::get) ?: catalogRow?.reasoningEffort,
            )
        }
    }
}
