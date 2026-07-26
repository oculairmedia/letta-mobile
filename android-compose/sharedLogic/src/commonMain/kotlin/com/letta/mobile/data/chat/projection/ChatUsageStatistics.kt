package com.letta.mobile.data.chat.projection

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UsageStatistics

/**
 * Platform-neutral chat usage statistics projected strictly from trustworthy,
 * monotonic stream timestamps and token counters.
 *
 * All metrics are nullable; absence ([null]) explicitly models missing, non-monotonic,
 * zero-token, or untrustworthy input data. No sentinel values (e.g. -1 or 0.0) are used.
 */
@Immutable
data class ChatUsageStatistics(
    /**
     * First-token latency (time to first output token/chunk) in milliseconds.
     * Derived from server-reported TTFT or monotonic timestamp deltas.
     * [null] if inputs are missing or non-monotonic.
     */
    val firstTokenLatencyMs: Long? = null,

    /**
     * Rate of output generation in tokens per second.
     * Derived strictly from positive completion token counts and active generation duration.
     * [null] if token counts are absent/<= 0 or duration is missing/<= 0 ms.
     */
    val outputTokensPerSecond: Double? = null,

    /**
     * Total completion/output tokens counted for this projection, if positive.
     */
    val completionTokens: Int? = null,

    /**
     * Active generation duration in milliseconds used for the output-rate calculation.
     */
    val generationDurationMs: Long? = null,
) {
    /**
     * First-token latency formatted as fractional seconds, if available.
     */
    val firstTokenLatencySeconds: Double?
        get() = firstTokenLatencyMs?.let { it.toDouble() / 1000.0 }

    /**
     * True if at least one trustworthy metric was derived.
     */
    val hasMetrics: Boolean
        get() = firstTokenLatencyMs != null || outputTokensPerSecond != null
}

/**
 * Projects [ChatUsageStatistics] from raw epoch millisecond timestamps and token counts.
 *
 * Validation rules:
 * - [firstTokenLatencyMs] requires valid [ttftNs] > 0, OR both [startEpochMs] and
 *   [firstTokenEpochMs] with [firstTokenEpochMs] >= [startEpochMs].
 * - [outputTokensPerSecond] requires [completionTokens] > 0 AND active generation duration > 0 ms.
 * - Non-monotonic timestamps ([firstTokenEpochMs] < [startEpochMs] or completion < start/firstToken)
 *   invalidate affected metrics.
 *
 * Returns [null] if no trustworthy metrics can be computed.
 */
fun projectChatUsageStatistics(
    startEpochMs: Long?,
    firstTokenEpochMs: Long?,
    completionEpochMs: Long?,
    completionTokens: Int?,
    ttftNs: Long? = null,
    totalDurationNs: Long? = null,
): ChatUsageStatistics? {
    val validCompletionTokens = completionTokens?.takeIf { it > 0 }

    val firstTokenLatencyMs = firstTokenLatencyMs(startEpochMs, firstTokenEpochMs, ttftNs)
    val generationDurationMs = generationDurationMs(
        startEpochMs = startEpochMs,
        firstTokenEpochMs = firstTokenEpochMs,
        completionEpochMs = completionEpochMs,
        totalDurationNs = totalDurationNs,
    )
    val outputTokensPerSecond = outputTokensPerSecond(validCompletionTokens, generationDurationMs)

    if (firstTokenLatencyMs == null && outputTokensPerSecond == null) {
        return null
    }

    return ChatUsageStatistics(
        firstTokenLatencyMs = firstTokenLatencyMs,
        outputTokensPerSecond = outputTokensPerSecond,
        completionTokens = validCompletionTokens,
        generationDurationMs = generationDurationMs,
    )
}

/**
 * Server-reported TTFT wins when present; otherwise the start -> first-token span, and only
 * when that span is monotonic. Absent means "not trustworthy", never zero.
 */
private fun firstTokenLatencyMs(
    startEpochMs: Long?,
    firstTokenEpochMs: Long?,
    ttftNs: Long?,
): Long? {
    if (ttftNs != null && ttftNs > 0L) return ttftNs / 1_000_000L
    if (firstTokenEpochMs == null || startEpochMs == null) return null
    if (firstTokenEpochMs < startEpochMs) return null
    return firstTokenEpochMs - startEpochMs
}

/**
 * True when the supplied timestamps contradict each other. Any contradiction poisons every
 * duration-derived metric: falling back to a wider span (start -> completion when completion
 * precedes the first token) would publish a plausible rate computed from data already known
 * to be untrustworthy.
 */
private fun timestampsContradictory(
    startEpochMs: Long?,
    firstTokenEpochMs: Long?,
    completionEpochMs: Long?,
): Boolean =
    (startEpochMs != null && firstTokenEpochMs != null && firstTokenEpochMs < startEpochMs) ||
        (firstTokenEpochMs != null && completionEpochMs != null && completionEpochMs < firstTokenEpochMs) ||
        (startEpochMs != null && completionEpochMs != null && completionEpochMs < startEpochMs)

/**
 * Active generation span, preferring first-token -> completion, then start -> completion, then
 * a server-reported total. Null whenever the timestamps contradict or the span is not positive.
 */
private fun generationDurationMs(
    startEpochMs: Long?,
    firstTokenEpochMs: Long?,
    completionEpochMs: Long?,
    totalDurationNs: Long?,
): Long? = when {
    timestampsContradictory(startEpochMs, firstTokenEpochMs, completionEpochMs) -> null
    completionEpochMs != null && firstTokenEpochMs != null ->
        (completionEpochMs - firstTokenEpochMs).takeIf { it > 0L }
    completionEpochMs != null && startEpochMs != null ->
        (completionEpochMs - startEpochMs).takeIf { it > 0L }
    totalDurationNs != null && totalDurationNs > 0L -> totalDurationNs / 1_000_000L
    else -> null
}

/** Output rate, or null when either input is absent or the result is not a finite positive. */
private fun outputTokensPerSecond(validCompletionTokens: Int?, generationDurationMs: Long?): Double? {
    if (validCompletionTokens == null || generationDurationMs == null || generationDurationMs <= 0L) return null
    val rate = validCompletionTokens.toDouble() / (generationDurationMs.toDouble() / 1000.0)
    return if (rate.isFinite() && rate > 0.0) rate else null
}

/**
 * Projects [ChatUsageStatistics] from ISO-8601 timestamp strings and token counts.
 */
fun projectChatUsageStatistics(
    startTimestampIso: String?,
    firstTokenTimestampIso: String?,
    completionTimestampIso: String?,
    completionTokens: Int?,
    ttftNs: Long? = null,
    totalDurationNs: Long? = null,
): ChatUsageStatistics? {
    val startEpochMs = startTimestampIso?.let(::parseTimestampEpochMillis)
    val firstTokenEpochMs = firstTokenTimestampIso?.let(::parseTimestampEpochMillis)
    val completionEpochMs = completionTimestampIso?.let(::parseTimestampEpochMillis)

    return projectChatUsageStatistics(
        startEpochMs = startEpochMs,
        firstTokenEpochMs = firstTokenEpochMs,
        completionEpochMs = completionEpochMs,
        completionTokens = completionTokens,
        ttftNs = ttftNs,
        totalDurationNs = totalDurationNs,
    )
}

/**
 * Projects [ChatUsageStatistics] from a [Run] and optional completion token count.
 */
fun projectChatUsageStatistics(
    run: Run,
    completionTokens: Int? = null,
): ChatUsageStatistics? = projectChatUsageStatistics(
    startTimestampIso = run.createdAt,
    firstTokenTimestampIso = null,
    completionTimestampIso = run.completedAt,
    completionTokens = completionTokens,
    ttftNs = run.ttftNs,
    totalDurationNs = run.totalDurationNs,
)

/**
 * Projects [ChatUsageStatistics] from a [Step] and optional [StepMetrics].
 */
fun projectChatUsageStatistics(
    step: Step,
    stepMetrics: StepMetrics? = null,
): ChatUsageStatistics? {
    val startNs = stepMetrics?.stepStartNs ?: stepMetrics?.llmRequestStartNs
    val startMs = startNs?.takeIf { it > 0L }?.let { it / 1_000_000L }
    val stepDurationNs = stepMetrics?.llmRequestNs ?: stepMetrics?.stepNs

    // StepMetrics carries no first-token timestamp: llmRequestStartNs marks when the
    // LLM request was *issued*, so treating it as a first-token mark would report
    // queue time as first-token latency. Absence is the trustworthy answer here —
    // only Run.ttftNs measures TTFT, so the Run overload is the one that reports it.
    return projectChatUsageStatistics(
        startEpochMs = startMs,
        firstTokenEpochMs = null,
        completionEpochMs = null,
        completionTokens = step.completionTokens,
        totalDurationNs = stepDurationNs,
    )
}

/**
 * Projects [ChatUsageStatistics] from a [UsageStatistics] payload and optional timestamp boundaries.
 */
fun projectChatUsageStatistics(
    usage: UsageStatistics,
    startTimestampIso: String? = null,
    firstTokenTimestampIso: String? = null,
    completionTimestampIso: String? = null,
): ChatUsageStatistics? = projectChatUsageStatistics(
    startTimestampIso = startTimestampIso,
    firstTokenTimestampIso = firstTokenTimestampIso,
    completionTimestampIso = completionTimestampIso ?: usage.date,
    completionTokens = usage.completionTokens,
)

/**
 * Projects [ChatUsageStatistics] from an ordered list of [UiMessage]s belonging to a turn or run.
 */
fun projectChatUsageStatistics(
    messages: List<UiMessage>,
    completionTokens: Int? = null,
): ChatUsageStatistics? {
    if (messages.isEmpty()) return null

    val userMessage = messages.firstOrNull { it.role == "user" }
    val assistantMessages = messages.filter { it.role == "assistant" }
    if (userMessage == null && assistantMessages.isEmpty()) return null

    val startIso = userMessage?.timestamp
    val firstAssistantIso = assistantMessages.firstOrNull()?.timestamp
    val lastAssistantIso = assistantMessages.lastOrNull()?.timestamp

    return projectChatUsageStatistics(
        startTimestampIso = startIso,
        firstTokenTimestampIso = firstAssistantIso,
        completionTimestampIso = lastAssistantIso,
        completionTokens = completionTokens,
    )
}
