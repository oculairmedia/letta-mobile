package com.letta.mobile.data.context

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

/**
 * letta-mobile-r2zo8: the latest context total per conversation, folded from the
 * App Server's `usage_statistics` frames.
 *
 * Every model call reports `context_tokens` — the whole prompt the call held, cached prefix
 * included. The latest one is how full the conversation's context is right now. The frame
 * carries a total only, never a breakdown.
 *
 * Only a frame that names its agent and conversation and carries a non-null count changes
 * anything, and it changes only that conversation's reading. `prompt_tokens` is never a
 * substitute: on a cached call it is just the uncached tail (73 tokens on a 29k prompt).
 */
fun reduceContextReadings(
    readings: Map<ContextReadingKey, Int>,
    frame: ServerFrame,
): Map<ContextReadingKey, Int> {
    val (key, tokens) = when (frame) {
        is ServerFrame.UsageStatistics -> frame.exactReading()
        is ServerFrame.RunActivity -> frame.compactionReading(readings)
        else -> null
    } ?: return readings
    // Re-inserted at the end, so the map stays in update order: the oldest entry is first,
    // which is what a bounded snapshot evicts.
    return if (readings[key] == tokens) readings else (readings - key) + (key to tokens)
}

/**
 * letta-mobile-kr39h: whether [frame] leaves its conversation's reading exact (`false`, a provider
 * total), estimated (`true`, a compaction's re-estimate) or untouched (null).
 */
fun contextReadingEstimate(frame: ServerFrame, readings: Map<ContextReadingKey, Int>): Pair<ContextReadingKey, Boolean>? =
    when (frame) {
        is ServerFrame.UsageStatistics -> frame.exactReading()?.let { (key, _) -> key to false }
        is ServerFrame.RunActivity -> frame.compactionReading(readings)?.let { (key, _) -> key to true }
        else -> null
    }

private fun ServerFrame.UsageStatistics.exactReading(): Pair<ContextReadingKey, Int>? {
    val key = readingKey() ?: return null
    val tokens = contextTokens?.toReadingTokens() ?: return null
    return key to tokens
}

/**
 * letta-mobile-kr39h: the reading a finished compaction leaves until the next model call reports
 * an exact total. Its `context_tokens_*` stats are chars/4 estimates of the transcript alone (no
 * system prompt, no tools), so the drop they describe is taken off the last exact total rather than
 * replacing it: replacing it would understate the context by everything outside the transcript.
 * Without a prior reading or both stats there is nothing honest to write.
 */
private fun ServerFrame.RunActivity.compactionReading(readings: Map<ContextReadingKey, Int>): Pair<ContextReadingKey, Int>? {
    val stats = (payload as? RuntimeEventPayload.CompactionFinished)?.stats ?: return null
    val key = contextReadingKeyOf(agentId, conversationId) ?: return null
    val previous = readings[key] ?: return null
    val before = stats.contextTokensBefore ?: return null
    val after = stats.contextTokensAfter ?: return null
    val dropped = (before - after).coerceAtLeast(0L)
    val estimate = (previous - dropped).coerceAtLeast(after).coerceAtMost(previous.toLong())
    return estimate.toReadingTokens()?.let { key to it }
}

private fun ServerFrame.UsageStatistics.readingKey(): ContextReadingKey? = contextReadingKeyOf(agentId, conversationId)

private fun Long.toReadingTokens(): Int? =
    takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

/**
 * Thin holder over [reduceContextReadings]: one per session graph, fed by
 * [observeContextReadings], read by every chat surface.
 *
 * @param initial readings to start from — what [ContextReadingSnapshots] saved last run.
 * @param onChange called with the new map whenever a frame changes it (to save it).
 */
class ContextTokenReadings(
    initial: Map<ContextReadingKey, Int> = emptyMap(),
    private val onChange: (Map<ContextReadingKey, Int>) -> Unit = {},
) {
    private val state = MutableStateFlow(initial)

    // Held across the update AND its onChange, so two concurrent records can never hand a save
    // the newer map before the older one.
    private val recordLock = Mutex()

    val readings: StateFlow<Map<ContextReadingKey, Int>> = state.asStateFlow()

    private val estimatedKeys = MutableStateFlow(emptySet<ContextReadingKey>())

    /**
     * letta-mobile-kr39h: conversations whose reading is a post-compaction estimate rather than a
     * provider total. Cleared per conversation by its next exact reading; not persisted (a restored
     * snapshot is treated as exact, as it always was).
     */
    val estimated: StateFlow<Set<ContextReadingKey>> = estimatedKeys.asStateFlow()

    suspend fun record(frame: ServerFrame) = recordLock.withLock {
        val before = state.value
        contextReadingEstimate(frame, before)?.let { (key, isEstimate) ->
            estimatedKeys.value = if (isEstimate) estimatedKeys.value + key else estimatedKeys.value - key
        }
        val after = reduceContextReadings(before, frame)
        if (after !== before) {
            state.value = after
            onChange(after)
        }
    }

    /** letta-mobile-kr39h: true while this conversation's reading is a post-compaction estimate. */
    fun isEstimated(agentId: String?, conversationId: String?): Boolean =
        contextReadingKeyOf(agentId, conversationId)?.let { it in estimated.value } ?: false

    fun latest(agentId: String?, conversationId: String?): Int? =
        readings.value.readingFor(agentId, conversationId)
}

/** Folds every frame of [frames] into [readings] until [scope] ends. */
fun CoroutineScope.observeContextReadings(
    frames: Flow<ServerFrame>,
    readings: ContextTokenReadings,
): Job = launch { frames.collect(readings::record) }

/**
 * A holder fed by [transport] for as long as [scope] lives — what a session graph owns. With
 * [snapshots], it starts from the last saved readings and saves every change, so the chip
 * survives a restart (letta-mobile-wdm6i).
 */
fun contextTokenReadingsOf(
    transport: IChannelTransport,
    scope: CoroutineScope,
    snapshots: ContextReadingSnapshots? = null,
): ContextTokenReadings =
    ContextTokenReadings(
        initial = snapshots?.load().orEmpty(),
        onChange = { snapshots?.save(it) },
    ).also { scope.observeContextReadings(transport.events, it) }
