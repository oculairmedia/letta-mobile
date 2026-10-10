package com.letta.mobile.data.compaction

import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.CompactionStats
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** letta-mobile-3kble: one conversation, as the in-flight guard keys it. */
data class CompactionKey(val agentId: String, val conversationId: String)

val CompactionRequest.key: CompactionKey get() = CompactionKey(agentId.value, wireConversationId)

/**
 * letta-mobile-3kble: the one entry point a UI uses to compact.
 *
 *  - A second tap on a conversation already compacting answers [CompactionOutcome.Busy] without
 *    sending anything; [compacting] says which conversations are busy so the button can disable.
 *  - [supported] turns false once the backend answers [CompactionOutcome.Unsupported], so the
 *    button hides instead of failing again (it is reset by a new controller, i.e. a new session).
 *  - A manual compaction streams no `usage_statistics`, so the conversation's streamed total goes
 *    stale. With the host's transcript estimate the reading is corrected the way an automatic
 *    compaction's `compaction_stats` correct it (and flagged estimated); without one it is flagged
 *    stale and keeps its number until the next turn.
 */
class CompactionController(
    private val repository: CompactionRepository,
    private val readings: ContextTokenReadings? = null,
) {
    private val running = MutableStateFlow(emptySet<CompactionKey>())
    val compacting: StateFlow<Set<CompactionKey>> = running.asStateFlow()

    private val support = MutableStateFlow<Boolean?>(null)

    /** Null until a compaction has been tried; false once the backend said it cannot compact. */
    val supported: StateFlow<Boolean?> = support.asStateFlow()

    suspend fun compact(request: CompactionRequest): CompactionOutcome {
        val key = request.key
        if (!claim(key)) return CompactionOutcome.Busy
        return try {
            repository.compact(request).also { outcome ->
                support.value = outcome !is CompactionOutcome.Unsupported
                applyToReading(key, outcome)
            }
        } finally {
            running.update { it - key }
        }
    }

    private fun claim(key: CompactionKey): Boolean {
        var claimed = false
        running.update { current ->
            claimed = key !in current
            if (claimed) current + key else current
        }
        return claimed
    }

    private suspend fun applyToReading(key: CompactionKey, outcome: CompactionOutcome) {
        val holder = readings ?: return
        when (outcome) {
            is CompactionOutcome.Compacted -> {
                val result = outcome.result
                if (result.contextTokensBefore != null && result.contextTokensAfter != null) {
                    holder.record(result.asCompactionFrame(key))
                } else {
                    holder.markStale(key.agentId, key.conversationId)
                }
            }
            CompactionOutcome.Pending -> holder.markStale(key.agentId, key.conversationId)
            else -> Unit
        }
    }

    private fun ConversationCompactResult.asCompactionFrame(key: CompactionKey) = ServerFrame.RunActivity(
        id = "manual-compaction-${key.agentId}-${key.conversationId}",
        ts = "",
        agentId = key.agentId,
        conversationId = key.conversationId,
        payload = RuntimeEventPayload.CompactionFinished(
            summary = summary.orEmpty(),
            stats = CompactionStats(
                trigger = MANUAL_TRIGGER,
                contextTokensBefore = contextTokensBefore,
                contextTokensAfter = contextTokensAfter,
                messagesCountBefore = messagesBefore,
                messagesCountAfter = messagesAfter,
            ),
        ),
    )

    private companion object {
        const val MANUAL_TRIGGER = "manual"
    }
}
