package com.letta.mobile.data.repository.modelcontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-okvyf: the model each conversation was switched to this session.
 *
 * A per-conversation switch (`model.update` / `update_model` with a
 * conversation id) leaves the agent's own `model` untouched, and a listed
 * `Conversation` does not carry its model. A picker that renders only the
 * agent's model therefore never moves after a switch. Pickers and composer
 * labels render [effectiveModel] / [resolve]: this session's pick for the conversation,
 * else the agent's model.
 */
class ConversationModelSelections {
    private val _byConversation = MutableStateFlow<Map<String, String>>(emptyMap())

    /** conversation id -> the model value last applied to it. */
    val byConversation: StateFlow<Map<String, String>> = _byConversation.asStateFlow()

    operator fun get(conversationId: String): String? = _byConversation.value[conversationId]

    /** Records [model] for [conversationId]; a blank model clears the entry. */
    fun record(conversationId: String, model: String?) {
        if (conversationId.isBlank()) return
        _byConversation.update { current ->
            if (model.isNullOrBlank()) current - conversationId else current + (conversationId to model)
        }
    }

    fun clear(conversationId: String) = record(conversationId, null)

    /** The model to show as selected for [conversationId] when its agent runs [agentModel]. */
    fun effectiveModel(conversationId: String?, agentModel: String?): String? =
        resolve(conversationOverride = conversationId?.let(::get), agentModel = agentModel)

    companion object {
        /** A conversation's own pick wins over its agent's model; blanks count as unset. */
        fun resolve(conversationOverride: String?, agentModel: String?): String? =
            conversationOverride?.takeIf { it.isNotBlank() } ?: agentModel?.takeIf { it.isNotBlank() }
    }
}
