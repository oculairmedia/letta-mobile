package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.Conversation

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

/** A model picked for one conversation in this session; a null [model] withdraws the pick. */
internal data class ConversationPick(val conversationId: String, val model: String?)

/**
 * The two sources of [ConversationModelChoices]: this session's picks and the host's records.
 * Not thread-safe; [ConversationModelSelections] serialises access.
 */
internal class ConversationModelLedger {
    private var picks: Map<String, String> = emptyMap()
    private var reports: Map<String, HostReport> = emptyMap()

    fun pickOf(conversationId: String): String? = picks[conversationId]

    fun pick(change: ConversationPick) {
        val model = change.model?.takeIf { it.isNotBlank() }
        picks = if (model == null) picks - change.conversationId else picks + (change.conversationId to model)
    }

    /** Takes each record as the host's word; a pick the host now reports is confirmed and dropped. */
    fun report(conversations: Iterable<Conversation>) {
        conversations.forEach { conversation ->
            val id = conversation.id.value.takeIf { it.isNotBlank() } ?: return@forEach
            val reported = HostReport.of(conversation)
            reports = if (reported == null) reports - id else reports + (id to reported)
            if (reported != null && picks[id] == reported.model) picks = picks - id
        }
    }

    /** The host's window and effort count only while they describe the model in force. */
    fun choices(): ConversationModelChoices {
        val models = HashMap<String, String>()
        val windows = HashMap<String, Int>()
        val efforts = HashMap<String, String>()
        (picks.keys + reports.keys).forEach { id ->
            val pick = picks[id]
            val reported = reports[id]
            (pick ?: reported?.model)?.let { models[id] = it }
            reported?.takeIf { it.describes(pick) }?.let { report ->
                report.window?.let { windows[id] = it }
                report.effort?.let { efforts[id] = it }
            }
        }
        return ConversationModelChoices(models, windows, efforts)
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
