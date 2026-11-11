package com.letta.mobile.data.context.estimate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The part of a local-backend transcript (`messages.jsonl`) that is in the
 * model's context right now, read the way letta-code reloads it.
 *
 * Two on-disk shapes exist:
 *  - session entries (0.29+): `{type:"message", id, message}` lines, and on compaction a
 *    `{type:"compaction", message: <summary>, firstKeptEntryId}` line. The context after it is the
 *    summary, the entries from `firstKeptEntryId` up to the compaction (sliding window), then
 *    everything appended later;
 *  - bare messages (0.26.1 and rewritten transcripts): the compaction summary is the message that
 *    carries `metadata.compaction`, and the file was rewritten so it heads the kept messages.
 *
 * Branching session trees (`parentId` forks) are read linearly, in file order.
 */
data class LocalTranscriptContext(
    /** The latest compaction's summary message, or null when the conversation was never compacted. */
    val summary: JsonObject?,
    /** In-context messages other than [summary], oldest first. */
    val messages: List<JsonObject>,
    /** Index into [messages] of the first message appended after the latest compaction. */
    val firstPostCompaction: Int,
) {
    /**
     * letta-code's `estimateLocalContextTokens`: the provider total the last assistant reply
     * recorded (`usage`), plus an estimate of what was appended after it. Null when no reply since
     * the latest compaction recorded usage: an older one predates the compaction and is stale.
     */
    fun recordedTotal(estimator: TokenEstimator = CharsPerToken): Int? {
        val index = lastUsageIndex() ?: return null
        val usage = usageTokens(messages[index]) ?: return null
        val trailing = LocalMessageTokens.ofAll(messages.subList(index + 1, messages.size), estimator)
        return (usage + trailing).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Counts text and fits usage growth in one pass; never crosses a compaction or model change. */
    internal fun messageCalibration(estimator: TokenEstimator): MessageCalibration {
        val run = messages.foldIndexed(UsageRun()) { index, accumulated, message ->
            val point = if (index >= firstPostCompaction) usagePoint(message, accumulated) else null
            accumulated.append(message, estimator, point)
        }
        return fit(run, estimator)
    }

    private fun usagePoint(message: JsonObject, run: UsageRun): UsagePoint? {
        if (!message.reportsFreshUsage(summary?.number("timestamp"))) return null
        val usage = message["usage"] as? JsonObject ?: return null
        val prompt = PROMPT_PARTS.sumOf { usage.number(it) ?: 0L }
        return prompt.takeIf { it > 0 }?.let { UsagePoint(run.chars, it, message.text("model")) }
    }

    private fun fit(run: UsageRun, estimator: TokenEstimator): MessageCalibration {
        val current = run.measuredFit()
        val selected = current ?: run.previousFit
        if (selected != null) return selected.project(run, reused = current == null)
        return boundedOverhead(run, estimator)
    }

    private fun boundedOverhead(run: UsageRun, estimator: TokenEstimator): MessageCalibration {
        val point = run.earliest ?: return MessageCalibration(run.fallback)
        val preceding = estimator.tokensForChars(point.chars)
        // Limit the assumed-density prefix to 10% of prompt; a long prefix can turn message error into named overhead.
        if (preceding > point.prompt * MAX_SINGLE_POINT_PREFIX_SHARE) return MessageCalibration(run.fallback)
        return MessageCalibration(run.fallback, fixed = (point.prompt - preceding).toInt())
    }

    private data class UsageFit(val density: Double, val fixed: Int) {
        fun project(run: UsageRun, reused: Boolean): MessageCalibration = MessageCalibration(
            kotlin.math.ceil(run.chars / density).toInt(), density, fixed, reused,
        )
    }

    /** Immutable, constant-space fold: latest candidate, earliest point, and most recent sufficient completed fit. */
    private data class UsageRun(
        val chars: Long = 0L,
        val fallback: Int = 0,
        val first: UsagePoint? = null,
        val last: UsagePoint? = null,
        val earliest: UsagePoint? = null,
        val previousFit: UsageFit? = null,
    ) {
        fun append(message: JsonObject, estimator: TokenEstimator, point: UsagePoint?): UsageRun {
            val addedChars = LocalMessageTokens.chars(message)
            return withPoint(point).copy(chars = chars + addedChars, fallback = fallback + estimator.tokensForChars(addedChars))
        }

        private fun withPoint(point: UsagePoint?): UsageRun = when {
            point == null -> this
            last?.precedes(point) == true -> copy(last = point)
            else -> copy(
                first = point,
                last = point,
                earliest = earliest ?: point,
                previousFit = measuredFit() ?: previousFit,
            )
        }

        fun measuredFit(): UsageFit? = last?.let { first?.fitTo(it) }
    }

    private data class UsagePoint(val chars: Long, val prompt: Long, val model: String?) {
        fun precedes(next: UsagePoint): Boolean = sameModel(next) && growsTo(next)

        private fun sameModel(next: UsagePoint): Boolean = model != null && model == next.model

        private fun growsTo(next: UsagePoint): Boolean = chars < next.chars && prompt < next.prompt

        fun fitTo(next: UsagePoint): UsageFit? {
            val density = densityTo(next) ?: return null
            val fixed = prompt - chars / density
            return fixed.takeIf { it >= 0 }?.let { UsageFit(density, it.toInt()) }
        }

        private fun densityTo(next: UsagePoint): Double? {
            if (!sameModel(next)) return null
            val growthChars = next.chars - chars
            val growthTokens = next.prompt - prompt
            if (!sufficientGrowth(next)) return null
            // Covers dense JSON through prose without trusting pathological slopes.
            return (growthChars.toDouble() / growthTokens).coerceIn(MIN_CHARS_PER_TOKEN, MAX_CHARS_PER_TOKEN)
        }

        private fun sufficientGrowth(next: UsagePoint): Boolean =
            next.chars - chars >= MIN_CALIBRATION_CHARS && next.prompt - prompt >= MIN_CALIBRATION_TOKENS
    }

    internal data class MessageCalibration(
        val tokens: Int,
        val charsPerToken: Double? = null,
        val fixed: Int? = null,
        val reusedFit: Boolean = false,
    )

    private fun lastUsageIndex(): Int? {
        val boundary = summary?.number("timestamp")
        for (index in messages.indices.reversed()) {
            if (index < firstPostCompaction) return null
            if (messages[index].reportsFreshUsage(boundary)) return index
        }
        return null
    }

    /** letta-code's `getAssistantUsageInfo` test, with its compaction-boundary staleness rule. */
    private fun JsonObject.reportsFreshUsage(boundary: Long?): Boolean =
        isSettledReply() && !predates(boundary) && usageTokens(this) != null

    private fun JsonObject.isSettledReply(): Boolean =
        text("role") == "assistant" && text("stopReason") !in UNUSABLE_STOP_REASONS

    /** At or before the latest compaction: letta-code treats such usage as stale. */
    private fun JsonObject.predates(boundary: Long?): Boolean {
        val stamp = number("timestamp") ?: return false
        return boundary != null && stamp <= boundary
    }

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reads [lines] of `messages.jsonl`; unreadable lines are skipped, as letta-code skips them. */
        fun read(lines: Sequence<String>): LocalTranscriptContext {
            val builder = Builder()
            lines.forEach { line ->
                val entry = line.trim().takeIf { it.isNotEmpty() }
                    ?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() as? JsonObject }
                if (entry != null) builder.accept(entry)
            }
            return builder.build()
        }

        /**
         * letta-code's `contextTokensFromLocalUsage`: `totalTokens`, else the sum of input, output
         * and cache reads/writes. Null when nothing positive was recorded.
         */
        fun usageTokens(message: JsonObject): Long? {
            val usage = message["usage"] as? JsonObject ?: return null
            usage.number("totalTokens")?.takeIf { it > 0 }?.let { return it }
            val parts = USAGE_PARTS.mapNotNull { usage.number(it) }
            if (parts.isEmpty()) return null
            return parts.sum().takeIf { it > 0 }
        }

        private const val MIN_CALIBRATION_CHARS = 16_384L
        private const val MIN_CALIBRATION_TOKENS = 4_096L
        private const val MAX_SINGLE_POINT_PREFIX_SHARE = 0.1
        private const val MIN_CHARS_PER_TOKEN = 1.0
        private const val MAX_CHARS_PER_TOKEN = 8.0
        private val PROMPT_PARTS = listOf("input", "cacheRead", "cacheWrite")
        private val USAGE_PARTS = listOf("input", "output", "cacheRead", "cacheWrite")
        private val UNUSABLE_STOP_REASONS = setOf("aborted", "error")
    }

    /** Folds transcript entries, in file order, into the in-context set. */
    private class Builder {
        private val entries = mutableListOf<Pair<String?, JsonObject>>()
        private var summary: JsonObject? = null
        private var firstPostCompaction = 0

        fun accept(entry: JsonObject) {
            when (entry.text("type")) {
                "message" -> (entry["message"] as? JsonObject)?.let { addMessage(entry.text("id"), it) }
                "compaction" -> (entry["message"] as? JsonObject)?.let { compact(it, entry.text("firstKeptEntryId")) }
                else -> if (entry.containsKey("role")) addMessage(null, entry)
            }
        }

        private fun addMessage(entryId: String?, message: JsonObject) {
            if (message.isCompactionSummary()) {
                compact(message, firstKeptEntryId = null)
                return
            }
            entries += entryId to message
        }

        private fun compact(summaryMessage: JsonObject, firstKeptEntryId: String?) {
            val keptFrom = firstKeptEntryId?.let { id -> entries.indexOfFirst { it.first == id } }?.takeIf { it >= 0 }
            val kept = keptFrom?.let { entries.subList(it, entries.size).toList() }.orEmpty()
            entries.clear()
            entries += kept
            summary = summaryMessage
            firstPostCompaction = kept.size
        }

        fun build() = LocalTranscriptContext(summary, entries.map { it.second }, firstPostCompaction)

        private fun JsonObject.isCompactionSummary(): Boolean =
            (this["metadata"] as? JsonObject)?.get("compaction") is JsonObject
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
}
