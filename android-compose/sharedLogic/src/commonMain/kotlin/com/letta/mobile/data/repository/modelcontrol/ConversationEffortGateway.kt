package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel

/**
 * One conversation's model switch together with what to do with its
 * reasoning effort ([ReasoningEffortChoice.Unchanged] leaves the effort alone).
 */
data class ConversationModelChange(
    val conversationId: ConversationId,
    val model: ModelHandle,
    val effort: ReasoningEffortChoice = ReasoningEffortChoice.Unchanged,
)

/**
 * A chat gateway whose per-conversation switch can carry a reasoning
 * effort (the wrapper's `model.update`). Probed by an interface check, like
 * `ConnectionStatusGateway`: a gateway without it (desktop's direct App Server session, which
 * switches through `conversation_update`) can change the model only, so a client hides its
 * effort chip there rather than offer a pick it cannot send.
 */
interface ConversationEffortGateway {
    /** Applies [change] to its conversation; returns the conversation as the host now records it. */
    suspend fun updateConversationModel(change: ConversationModelChange): Conversation
}

/**
 * The reasoning efforts the host catalog [rows] advertise for [model], a
 * row of a chat's own model list. Matched on the model's handle, id or selection aliases, since a
 * chat list and the catalog name the same route either way; empty when no row advertises any.
 */
fun reasoningEffortsOf(rows: List<CatalogModel>, model: LlmModel): List<String> {
    if (rows.isEmpty()) return emptyList()
    val names = (listOfNotNull(model.handle, model.id) + model.selectionAliases).filter(String::isNotBlank).toSet()
    return rows.firstOrNull { it.handle.value in names && it.reasoningEfforts.isNotEmpty() }?.reasoningEfforts.orEmpty()
}
