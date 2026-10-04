package com.letta.mobile.data.context

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
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
    val usage = frame as? ServerFrame.UsageStatistics ?: return readings
    val key = usage.readingKey() ?: return readings
    val tokens = usage.contextTokens?.toReadingTokens() ?: return readings
    // Re-inserted at the end, so the map stays in update order: the oldest entry is first,
    // which is what a bounded snapshot evicts.
    return if (readings[key] == tokens) readings else (readings - key) + (key to tokens)
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

    val readings: StateFlow<Map<ContextReadingKey, Int>> = state.asStateFlow()

    fun record(frame: ServerFrame) {
        val before = state.value
        val after = state.updateAndGet { reduceContextReadings(it, frame) }
        if (after !== before) onChange(after)
    }

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
