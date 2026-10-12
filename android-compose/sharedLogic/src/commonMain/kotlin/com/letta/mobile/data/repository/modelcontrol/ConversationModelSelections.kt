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
 * letta-mobile-dxz5s: what each conversation runs, as pickers, composer labels and context meters
 * read it. [models] is the conversation's model where it differs from its agent's; [windows] and
 * [efforts] are the host's figures for that model, present only while they describe it.
 */
data class ConversationModelChoices(
    val models: Map<String, String> = emptyMap(),
    val windows: Map<String, Int> = emptyMap(),
    val efforts: Map<String, String> = emptyMap(),
) {
    /** The conversation's own model; null means it runs its agent's. */
    operator fun get(conversationId: String): String? = models[conversationId]

    /** The conversation's own window (host record), when it describes the model in [get]. */
    fun windowOf(conversationId: String): Int? = windows[conversationId]

    /** The conversation's own reasoning effort (host record), when it describes the model in [get]. */
    fun effortOf(conversationId: String): String? = efforts[conversationId]
}

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
    private var local: Map<String, String> = emptyMap()
    private var host: Map<String, HostConversationModel> = emptyMap()
    private val _choices = MutableStateFlow(ConversationModelChoices())

    /** Every conversation's model, window and effort, as [ConversationModelChoices]. */
    val choices: StateFlow<ConversationModelChoices> = _choices.asStateFlow()

    operator fun get(conversationId: String): String? = _choices.value[conversationId]

    /** Records [model] as this session's pick for [conversationId]; a blank model clears the pick. */
    fun record(conversationId: String, model: String?) {
        if (conversationId.isBlank()) return
        mutate {
            local = if (model.isNullOrBlank()) local - conversationId else local + (conversationId to model)
        }
    }

    fun clear(conversationId: String) = record(conversationId, null)

    /**
     * Undoes a failed switch of [conversationId] to [model] by restoring
     * [previous], unless a newer pick has replaced [model] meanwhile: an
     * earlier failure must not undo it. True when it restored.
     */
    fun rollback(conversationId: String, model: String, previous: String?): Boolean = synchronized(lock) {
        if (local[conversationId] != model) return false
        local = if (previous.isNullOrBlank()) local - conversationId else local + (conversationId to previous)
        publish()
        true
    }

    /**
     * Takes the host's word for each of [conversations]: its model override (none means it runs its
     * agent's model) and the window and effort the host records for it. A pick made this session
     * stays in force until the host reports that same model.
     */
    fun seedFromHost(conversations: Iterable<Conversation>) = mutate {
        conversations.forEach { conversation ->
            val id = conversation.id.value.takeIf { it.isNotBlank() } ?: return@forEach
            val reported = HostConversationModel.of(conversation)
            host = if (reported == null) host - id else host + (id to reported)
            val pick = local[id]
            if (pick != null && pick == reported?.model) local = local - id
        }
    }

    /** Follows a conversation list (cache plus refreshes) until cancelled; a failing source stops quietly. */
    suspend fun followHost(conversations: Flow<List<Conversation>>) {
        conversations
            .catch { e -> if (e is CancellationException) throw e }
            .collect { seedFromHost(it) }
    }

    /** The model to show as selected for [conversationId] when its agent runs [agentModel]. */
    fun effectiveModel(conversationId: String?, agentModel: String?): String? =
        resolve(conversationOverride = conversationId?.let(::get), agentModel = agentModel)

    private inline fun mutate(change: () -> Unit) = synchronized(lock) {
        change()
        publish()
    }

    /** Called under [lock]: the host's window and effort count only while they describe the model in force. */
    private fun publish() {
        val ids = local.keys + host.keys
        val models = HashMap<String, String>()
        val windows = HashMap<String, Int>()
        val efforts = HashMap<String, String>()
        ids.forEach { id ->
            val pick = local[id]
            val reported = host[id]
            (pick ?: reported?.model)?.let { models[id] = it }
            if (reported != null && (pick == null || pick == reported.model)) {
                reported.window?.let { windows[id] = it }
                reported.effort?.let { efforts[id] = it }
            }
        }
        _choices.value = ConversationModelChoices(models, windows, efforts)
    }

    /** What the host records for one conversation; null when it records nothing of its own. */
    private data class HostConversationModel(val model: String?, val window: Int?, val effort: String?) {
        companion object {
            fun of(conversation: Conversation): HostConversationModel? {
                val reported = HostConversationModel(
                    model = conversation.model?.takeIf { it.isNotBlank() },
                    window = conversation.hostContextWindowLimit,
                    effort = conversation.modelSettings?.effectiveEffort,
                )
                return reported.takeIf { it.model != null || it.window != null || it.effort != null }
            }
        }
    }

    companion object {
        /** A conversation's own pick wins over its agent's model; blanks count as unset. */
        fun resolve(conversationOverride: String?, agentModel: String?): String? =
            conversationOverride?.takeIf { it.isNotBlank() } ?: agentModel?.takeIf { it.isNotBlank() }
    }
}
