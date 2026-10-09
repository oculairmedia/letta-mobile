package com.letta.mobile.data.context.estimate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * letta-mobile-cyh28: the part of a local-backend transcript (`messages.jsonl`) that is in the
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

    private fun lastUsageIndex(): Int? {
        val boundary = summary?.number("timestamp")
        for (index in messages.indices.reversed()) {
            if (index < firstPostCompaction) return null
            if (messages[index].reportsFreshUsage(boundary)) return index
        }
        return null
    }

    /** letta-code's `getAssistantUsageInfo` test, with its compaction-boundary staleness rule. */
    private fun JsonObject.reportsFreshUsage(boundary: Long?): Boolean {
        if (text("role") != "assistant" || text("stopReason") in UNUSABLE_STOP_REASONS) return false
        val stamp = number("timestamp")
        if (boundary != null && stamp != null && stamp <= boundary) return false
        return usageTokens(this) != null
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
