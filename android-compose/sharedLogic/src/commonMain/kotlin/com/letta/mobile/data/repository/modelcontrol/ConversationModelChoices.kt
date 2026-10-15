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
 * read it, keyed by conversation id. [models] holds the conversation's model where it differs from
 * its agent's (none means it runs the agent's); [windows] and [efforts] are the host's figures for
 * that model, present only while they describe it.
 */
data class ConversationModelChoices(
    val models: Map<String, String> = emptyMap(),
    val windows: Map<String, Int> = emptyMap(),
    val efforts: Map<String, String> = emptyMap(),
)

/**
 * letta-mobile-dxz5s: seeds these selections from a conversation list (cache plus refreshes) until
 * cancelled; a failing source stops quietly.
 */
suspend fun ConversationModelSelections.followHost(conversations: Flow<List<Conversation>>) {
    conversations
        .catch { e -> if (e is CancellationException) throw e }
        .collect { seedFromHost(it) }
}

/**
 * A model picked for one conversation in this session; a null [model] withdraws the pick.
 * [effort] is the reasoning effort sent with it (letta-mobile-by4vm.82): a named effort shows at
 * once, before the host's record catches up.
 */
internal data class ConversationPick(
    val conversationId: String,
    val model: String?,
    val effort: ReasoningEffortChoice = ReasoningEffortChoice.Unchanged,
)

/**
 * The two sources of [ConversationModelChoices]: this session's picks and the host's records.
 * Every change is applied under one lock and published to [choices] before the lock is released.
 */
internal class ConversationModelLedger {
    private val lock = SynchronizedObject()
    private var picks: Map<String, PendingPick> = emptyMap()
    private var reports: Map<String, HostReport> = emptyMap()
    private val _choices = MutableStateFlow(ConversationModelChoices())

    val choices: StateFlow<ConversationModelChoices> = _choices.asStateFlow()

    fun pick(change: ConversationPick) = update { set(change) }

    /** Restores [previous] in place of [failed], unless a newer pick replaced [failed] meanwhile. True when it restored. */
    fun replace(failed: ConversationPick, previous: ConversationPick): Boolean = update {
        val current = picks[failed.conversationId]?.model == failed.model
        if (current) set(previous)
        current
    }

    /** Takes each record as the host's word; a pick the host now reports is confirmed and dropped. */
    fun report(conversations: Iterable<Conversation>) = update {
        conversations.forEach { conversation ->
            val id = conversation.id.value.takeIf { it.isNotBlank() } ?: return@forEach
            val reported = HostReport.of(conversation)
            reports = if (reported == null) reports - id else reports + (id to reported)
            if (reported != null && picks[id]?.confirmedBy(reported) == true) picks = picks - id
        }
    }

    private fun set(change: ConversationPick) {
        val model = change.model?.takeIf { it.isNotBlank() }
        picks = if (model == null) picks - change.conversationId else picks + (change.conversationId to pending(change, model))
    }

    /** Re-picking the same model without an effort keeps the effort still pending for it. */
    private fun pending(change: ConversationPick, model: String): PendingPick {
        val kept = picks[change.conversationId]
            ?.takeIf { it.model == model && change.effort == ReasoningEffortChoice.Unchanged }
            ?.effort
        return PendingPick(model, kept ?: change.effort)
    }

    private inline fun <T> update(change: () -> T): T = synchronized(lock) {
        change().also { _choices.value = merged() }
    }

    /** The host's window and effort count only while they describe the model in force. */
    private fun merged(): ConversationModelChoices {
        val models = HashMap<String, String>()
        val windows = HashMap<String, Int>()
        val efforts = HashMap<String, String>()
        (picks.keys + reports.keys).forEach { id ->
            val pick = picks[id]
            val reported = reports[id]
            (pick?.model ?: reported?.model)?.let { models[id] = it }
            val report = reported?.takeIf { it.describes(pick?.model) }
            report?.window?.let { windows[id] = it }
            (if (pick != null) pick.shownEffort(report) else report?.effort)?.let { efforts[id] = it }
        }
        return ConversationModelChoices(models, windows, efforts)
    }

    /** A pick not yet confirmed by the host: its model and the effort sent with it. */
    private data class PendingPick(val model: String, val effort: ReasoningEffortChoice) {
        /** The host reports this pick: same model and, for a named effort, that effort. */
        fun confirmedBy(report: HostReport): Boolean = report.model == model &&
            (effort !is ReasoningEffortChoice.Named || report.effort.equals(effort.effort, ignoreCase = true))

        /** The effort to show while pending; a provider-default pick shows none whatever [report] says. */
        fun shownEffort(report: HostReport?): String? = when (effort) {
            is ReasoningEffortChoice.Named -> effort.effort
            ReasoningEffortChoice.ProviderDefault -> null
            ReasoningEffortChoice.Unchanged -> report?.effort
        }
    }

    /** What the host records for one conversation. */
    private data class HostReport(val model: String?, val window: Int?, val effort: String?) {
        /** True when this report is about the model a pending [pick] (if any) selects. */
        fun describes(pick: String?): Boolean = pick == null || pick == model

        companion object {
            /** Null when the host records nothing of the conversation's own. */
            fun of(conversation: Conversation): HostReport? = HostReport(
                model = conversation.model?.takeIf { it.isNotBlank() },
                window = conversation.hostContextWindowLimit,
                effort = conversation.modelSettings?.effectiveEffort,
            ).takeUnless { it == EMPTY }

            private val EMPTY = HostReport(null, null, null)
        }
    }
}
