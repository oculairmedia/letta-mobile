package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.Conversation
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch

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
    private val lock = SynchronizedObject()
    private val ledger = ConversationModelLedger()
    private val _choices = MutableStateFlow(ConversationModelChoices())

    /** Every conversation's model, window and effort. */
    val choices: StateFlow<ConversationModelChoices> = _choices.asStateFlow()

    operator fun get(conversationId: String): String? = _choices.value.models[conversationId]

    /** Records [model] as this session's pick for [conversationId]; a blank model clears the pick. */
    fun record(conversationId: String, model: String?) {
        if (conversationId.isBlank()) return
        update { ledger.pick(ConversationPick(conversationId, model)) }
    }

    fun clear(conversationId: String) = record(conversationId, null)

    /**
     * Undoes a failed switch of [conversationId] to [model] by restoring
     * [previous], unless a newer pick has replaced [model] meanwhile: an
     * earlier failure must not undo it. True when it restored.
     */
    fun rollback(conversationId: String, model: String, previous: String?): Boolean = update {
        val current = ledger.pickOf(conversationId) == model
        if (current) ledger.pick(ConversationPick(conversationId, previous))
        current
    }

    /**
     * Takes the host's word for each of [conversations]: its model override (none means it runs its
     * agent's model) and the window and effort the host records for it. A pick made this session
     * stays in force until the host reports that same model.
     */
    fun seedFromHost(conversations: Iterable<Conversation>) = update { ledger.report(conversations) }

    /** Follows a conversation list (cache plus refreshes) until cancelled; a failing source stops quietly. */
    suspend fun followHost(conversations: Flow<List<Conversation>>) {
        conversations
            .catch { e -> if (e is CancellationException) throw e }
            .collect { seedFromHost(it) }
    }

    /** The model to show as selected for [conversationId] when its agent runs [agentModel]. */
    fun effectiveModel(conversationId: String?, agentModel: String?): String? =
        resolve(conversationOverride = conversationId?.let(::get), agentModel = agentModel)

    private inline fun <T> update(change: () -> T): T = synchronized(lock) {
        change().also { _choices.value = ledger.choices() }
    }

    companion object {
        /** A conversation's own pick wins over its agent's model; blanks count as unset. */
        fun resolve(conversationOverride: String?, agentModel: String?): String? =
            conversationOverride?.takeIf { it.isNotBlank() } ?: agentModel?.takeIf { it.isNotBlank() }
    }
}
