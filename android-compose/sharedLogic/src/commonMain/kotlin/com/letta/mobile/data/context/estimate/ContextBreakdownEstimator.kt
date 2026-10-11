package com.letta.mobile.data.context.estimate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** letta-mobile-cyh28: the four sections a local-backend prompt can be split into from disk. */
data class ContextSections(
    val system: Int,
    val memory: Int,
    val summary: Int,
    val messages: Int,
) {
    val total: Int get() = system + memory + summary + messages

    internal fun asList(): List<Int> = listOf(system, memory, summary, messages)

    internal companion object {
        fun of(values: List<Int>) = ContextSections(values[0], values[1], values[2], values[3])
    }
}

/** Where the exact total a breakdown was matched to came from. */
enum class ContextTotalSource(val wire: String) {
    /** The client's latest streamed `usage_statistics.context_tokens`. */
    Client("client"),

    /** The `usage` the last assistant reply recorded on disk, plus what was appended after it. */
    Recorded("recorded"),
}

/**
 * letta-mobile-cyh28: an estimated breakdown of one conversation's context.
 *
 * With a [totalSource] the rows are matched to that exact total: [tools] is the residual (tool
 * schemas live only inside the running process, never on disk; it also absorbs reasoning tokens
 * and cache rounding, hence "Tools & other"), or, when the disk sections alone exceed the total,
 * the sections are scaled down and [tools] is 0. Either way the five rows sum to [total] exactly.
 * Without a total [tools] is null and [total] is the partial sum of the four sections.
 */
data class ContextBreakdownEstimate(
    val sections: ContextSections,
    val tools: Int?,
    val total: Int,
    val totalSource: ContextTotalSource?,
    val scaledDown: Boolean,
    val window: ContextWindowSize?,
    val messageCount: Int,
    /** False when the system prompt sidecar has no `coreMemory` (letta-code 0.26.1): memory is inside system. */
    val memorySplit: Boolean,
    val systemPrompt: String,
    val coreMemory: String,
) {
    val calibrated: Boolean get() = totalSource != null
}

/** A context window and the record field it was read from. */
data class ContextWindowSize(val tokens: Int, val source: String)

/** Everything [ContextBreakdownEstimator.estimate] reads; all of it comes from the local store. */
data class LocalContextInputs(
    /** `conversations/<key>/system-prompt.json`: `{content, coreMemory?}`. */
    val systemPromptSidecar: JsonObject?,
    val agent: JsonObject,
    val conversation: JsonObject?,
    val transcript: LocalTranscriptContext,
    /** The client's latest streamed total; null when it has none or knows it is stale. */
    val reportedTotal: Int?,
)

object ContextBreakdownEstimator {
    fun estimate(inputs: LocalContextInputs, estimator: TokenEstimator = CharsPerToken): ContextBreakdownEstimate {
        val prompt = splitPrompt(inputs)
        val transcript = inputs.transcript
        val sections = ContextSections(
            system = estimator.tokensForChars(prompt.systemChars),
            memory = estimator.tokensForChars(prompt.coreMemory.length.toLong()),
            summary = transcript.summary?.let { LocalMessageTokens.of(it, estimator) } ?: 0,
            messages = LocalMessageTokens.ofAll(transcript.messages, estimator),
        )
        val (total, source) = exactTotal(inputs, estimator)
        val calibrated = calibrate(sections, total)
        return ContextBreakdownEstimate(
            sections = calibrated.sections,
            tools = calibrated.tools,
            total = calibrated.total,
            totalSource = source.takeIf { total != null },
            scaledDown = calibrated.scaledDown,
            window = windowOf(inputs.agent, inputs.conversation),
            messageCount = transcript.messages.size + (if (transcript.summary != null) 1 else 0),
            memorySplit = prompt.memorySplit,
            systemPrompt = prompt.content,
            coreMemory = prompt.coreMemory,
        )
    }

    /** The result of matching [ContextSections] to an exact total. */
    data class Calibration(val sections: ContextSections, val tools: Int?, val total: Int, val scaledDown: Boolean)

    /**
     * Matches [sections] to [reportedTotal] so the rows sum to it exactly: the residual becomes
     * tools when the total holds the sections, else the sections shrink in proportion (largest
     * remainder, so no token is lost to rounding). Without a total nothing changes.
     */
    fun calibrate(sections: ContextSections, reportedTotal: Int?): Calibration {
        val sum = sections.total
        val total = reportedTotal?.coerceAtLeast(0) ?: return Calibration(sections, tools = null, total = sum, scaledDown = false)
        if (total >= sum) return Calibration(sections, tools = total - sum, total = total, scaledDown = false)
        return Calibration(ContextSections.of(scaleTo(sections.asList(), total)), tools = 0, total = total, scaledDown = true)
    }

    /** Hamilton's method: floors first, then the leftover tokens to the largest remainders. */
    private fun scaleTo(values: List<Int>, target: Int): List<Int> {
        val sum = values.sumOf { it.toLong() }
        if (sum == 0L) return values.map { 0 }
        val exact = values.map { it.toLong() * target }
        val floors = exact.map { (it / sum).toInt() }.toMutableList()
        var leftover = target - floors.sum()
        exact.withIndex()
            .sortedByDescending { (_, scaled) -> scaled % sum }
            .forEach { (index, _) ->
                if (leftover > 0) {
                    floors[index] += 1
                    leftover -= 1
                }
            }
        return floors
    }

    private fun exactTotal(inputs: LocalContextInputs, estimator: TokenEstimator): Pair<Int?, ContextTotalSource> {
        inputs.reportedTotal?.takeIf { it > 0 }?.let { return it to ContextTotalSource.Client }
        return inputs.transcript.recordedTotal(estimator) to ContextTotalSource.Recorded
    }

    private data class PromptSplit(val content: String, val coreMemory: String, val memorySplit: Boolean) {
        val systemChars: Long get() = (content.length - coreMemory.length).coerceAtLeast(0).toLong()
    }

    /**
     * The compiled prompt is `injectCoreMemory(agent.system, coreMemory)`; 0.29+ stores both, so
     * memory is `coreMemory` and the system prompt is the rest. 0.26.1 stores `content` only, and
     * the whole of it counts as system.
     */
    private fun splitPrompt(inputs: LocalContextInputs): PromptSplit {
        val sidecar = inputs.systemPromptSidecar
        val content = sidecar?.text("content") ?: inputs.agent.text("system").orEmpty()
        val coreMemory = sidecar?.text("coreMemory")
        return if (coreMemory == null) {
            PromptSplit(content, coreMemory = "", memorySplit = false)
        } else {
            PromptSplit(content, coreMemory.take(content.length), memorySplit = true)
        }
    }

    /**
     * letta-code's `effectiveContextWindow`: the conversation's limit, then its model settings,
     * then the agent's model settings; then the fields older stores carry. Null when no record
     * names one (the client then falls back to the model catalog).
     */
    fun windowOf(agent: JsonObject, conversation: JsonObject?): ContextWindowSize? {
        val candidates = listOf(
            "conversation.context_window_limit" to conversation?.number("context_window_limit"),
            "conversation.model_settings.context_window_limit" to conversation?.obj("model_settings")?.number("context_window_limit"),
            "agent.model_settings.context_window_limit" to agent.obj("model_settings")?.number("context_window_limit"),
            "agent.context_window_limit" to agent.number("context_window_limit"),
            "agent.llm_config.context_window" to agent.obj("llm_config")?.number("context_window"),
        )
        return candidates.firstNotNullOfOrNull { (source, tokens) ->
            tokens?.takeIf { it > 0 }?.let { ContextWindowSize(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), source) }
        }
    }
}

/**
 * The `agent.context` answer: the legacy overview fields (so an older client still reads it) plus
 * `source`, `calibrated`, `tools_derived` and the rest that say how far to trust it.
 */
fun ContextBreakdownEstimate.toAgentContextJson(): JsonObject = buildJsonObject {
    put("context_window_size_current", total)
    put("context_window_size_max", window?.tokens ?: 0)
    put("num_messages", messageCount)
    put("num_archival_memory", 0)
    put("num_recall_memory", messageCount)
    put("num_tokens_external_memory_summary", 0)
    put("num_tokens_system", sections.system)
    put("num_tokens_core_memory", sections.memory)
    put("num_tokens_summary_memory", sections.summary)
    put("num_tokens_messages", sections.messages)
    put("num_tokens_functions_definitions", tools ?: 0)
    put("num_tokens_memory_filesystem", 0)
    put("num_tokens_tool_usage_rules", 0)
    put("num_tokens_directories", 0)
    put("external_memory_summary", "")
    put("system_prompt", systemPrompt)
    put("core_memory", coreMemory)
    put("summary_memory", JsonNull)
    put("memory_filesystem", JsonNull)
    put("tool_usage_rules", JsonNull)
    put("directories", JsonArray(emptyList()))
    put("messages", JsonArray(emptyList()))
    put("functions_definitions", JsonArray(emptyList()))
    put("source", ESTIMATE_SOURCE)
    put("calibrated", calibrated)
    put("tools_derived", tools != null)
    totalSource?.let { put("total_source", it.wire) }
    put("scaled_down", scaledDown)
    put("memory_split", memorySplit)
    window?.let { put("window_source", it.source) }
}

/** `agent.context`'s `source` for an answer computed by [ContextBreakdownEstimator]. */
const val ESTIMATE_SOURCE: String = "estimate"

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.number(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content.toLongOrNull()
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
}
