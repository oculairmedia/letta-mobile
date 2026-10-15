package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.Conversation
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-okvyf / letta-mobile-dxz5s: each conversation's model.
 *
 * A per-conversation switch (`model.update` / `update_model` with a conversation id) leaves the
 * agent's own `model` untouched. Two sources say which model a conversation runs:
 *
 *  - the host: a listed or fetched [Conversation] carries `model` / `model_settings` /
 *    `context_window_limit` ([seedFromHost]), so an override made anywhere (CLI, desktop, another
 *    phone) shows after a cold start;
 *  - this session: a switch made here ([record]) wins until the host reports the same model, which
 *    confirms it, so a list fetched before the switch landed cannot undo it.
 *
 * Pickers and composer labels render [effectiveModel] / [resolve]: the conversation's model, else
 * the agent's.
 */
class ConversationModelSelections {
    private val ledger = ConversationModelLedger()

    /** Every conversation's model, window and effort. */
    val choices: StateFlow<ConversationModelChoices> = ledger.choices

    operator fun get(conversationId: String): String? = choices.value.models[conversationId]

    /**
     * Records [model] (with the [effort] sent with it) as this session's pick for [conversationId];
     * a blank model clears the pick.
     */
    fun record(conversationId: String, model: String?, effort: ReasoningEffortChoice = ReasoningEffortChoice.Unchanged) {
        if (conversationId.isNotBlank()) ledger.pick(ConversationPick(conversationId, model, effort))
    }

    /**
     * Undoes a failed switch of [conversationId] to [model] by restoring
     * [previous], unless a newer pick has replaced [model] meanwhile: an
     * earlier failure must not undo it. True when it restored.
     */
    fun rollback(conversationId: String, model: String, previous: String?): Boolean =
        ledger.replace(failed = ConversationPick(conversationId, model), previous = ConversationPick(conversationId, previous))

    /**
     * Takes the host's word for each of [conversations]: its model override (none means it runs its
     * agent's model) and the window and effort the host records for it. A pick made this session
     * stays in force until the host reports that same model.
     */
    fun seedFromHost(conversations: Iterable<Conversation>) = ledger.report(conversations)

    /** The model to show as selected for [conversationId] when its agent runs [agentModel]. */
    fun effectiveModel(conversationId: String?, agentModel: String?): String? =
        resolve(conversationOverride = conversationId?.let(::get), agentModel = agentModel)

    companion object {
        /** A conversation's own pick wins over its agent's model; blanks count as unset. */
        fun resolve(conversationOverride: String?, agentModel: String?): String? =
            conversationOverride?.takeIf { it.isNotBlank() } ?: agentModel?.takeIf { it.isNotBlank() }
    }
}