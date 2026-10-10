package com.letta.mobile.data.context.limit

import com.letta.mobile.data.compaction.CompactionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The conversation a limit request is for, keyed like compaction (bare `default` for the default conversation). */
val ContextLimitRequest.key: CompactionKey get() = CompactionKey(agentId.value, wireConversationId)

/**
 * A limit this session applied, and the model it was applied under: until the host's records are
 * re-read it is the window the meter draws against.
 */
data class AppliedContextLimit(val tokens: Int, val scope: ContextLimitScope?, val modelValue: String?)

/**
 * letta-mobile-joigh: the one entry point a UI uses to change the context limit.
 *
 *  - [applying] says which conversations have a change in flight, so the slider disables; a
 *    second change while one runs is not sent.
 *  - [supported] turns false once the backend answers [ContextLimitOutcome.Unsupported], so the
 *    slider gives way to a reason instead of failing again (reset by a new session's controller).
 *  - [applied] remembers each successful change: neither the agent cache nor a direct App Server
 *    session re-reads the record on its own, so without it the meter would keep the old window.
 */
class ContextLimitController(private val repository: ContextLimitRepository) {
    private val running = MutableStateFlow(emptySet<CompactionKey>())
    val applying: StateFlow<Set<CompactionKey>> = running.asStateFlow()

    private val support = MutableStateFlow<Boolean?>(null)
    val supported: StateFlow<Boolean?> = support.asStateFlow()

    private val limits = MutableStateFlow(emptyMap<CompactionKey, AppliedContextLimit>())
    val applied: StateFlow<Map<CompactionKey, AppliedContextLimit>> = limits.asStateFlow()

    /** Applies [request]; [modelValue] is the model it is meant for (an applied limit follows that model only). */
    suspend fun apply(request: ContextLimitRequest, modelValue: String?): ContextLimitOutcome? {
        val key = request.key
        if (!claim(key)) return null
        return try {
            repository.apply(request).also { outcome ->
                support.value = outcome !is ContextLimitOutcome.Unsupported
                if (outcome is ContextLimitOutcome.Applied) {
                    val result = outcome.result
                    limits.update { it + (key to AppliedContextLimit(result.contextWindow, result.scope, modelValue)) }
                }
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
}
