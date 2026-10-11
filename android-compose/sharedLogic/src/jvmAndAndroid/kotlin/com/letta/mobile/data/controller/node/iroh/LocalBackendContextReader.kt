package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.context.estimate.ContextBreakdownEstimator
import com.letta.mobile.data.context.estimate.LocalContextInputs
import com.letta.mobile.data.context.estimate.LocalMessageTokens
import com.letta.mobile.data.context.estimate.LocalTranscriptContext
import com.letta.mobile.data.context.estimate.toAgentContextJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** letta-mobile-cyh28: one `agent.context` request. */
data class AgentContextQuery(
    val agentId: String,
    val conversationId: String?,
    /** The client's latest streamed `context_tokens`, to match the sections to. */
    val reportedTotal: Int? = null,
)

/**
 * lgns8.9 / letta-mobile-cyh28: on-disk `agent.context` reader.
 *
 * There is no Letta server behind the local backend, and letta-code itself reports only the
 * provider total (`usage_statistics.context_tokens`). This reader estimates the split from the
 * store with [ContextBreakdownEstimator]: system prompt and memory from the conversation's
 * `system-prompt.json` sidecar, the compaction summary and the in-context messages from
 * `messages.jsonl` (read as letta-code reloads it), the window from the conversation/agent
 * records. The client passes its latest streamed total as `reported_total`; failing that the
 * total the last reply recorded on disk is used. Matched to either, the rows sum to the exact
 * total with tool schemas as the residual; with neither, the answer is the partial, uncalibrated
 * sum. Every answer says so (`source`, `calibrated`, `tools_derived`).
 *
 * READ-ONLY by construction: no method here opens a file for writing.
 */
internal class LocalBackendContextReader(
    private val support: LocalBackendStoreSupport,
    private val messageReader: LocalBackendMessageReader,
    private val maxTranscriptBytes: Long = LocalBackendMessageReader.MAX_TRANSCRIPT_BYTES,
) {

    /**
     * Returns null when the agent is unknown or the store cannot be read, so the caller fails
     * closed rather than serving a hollow context window.
     */
    fun agentContextProjected(query: AgentContextQuery): JsonObject? =
        runCatching {
            val agent = readJson(File(File(support.baseDir, "agents"), "${query.agentId}.json")) ?: return@runCatching null
            val dir = messageReader.contextConversationDir(query.conversationId, query.agentId) ?: return@runCatching null
            val inputs = LocalContextInputs(
                systemPromptSidecar = readJson(File(dir, "system-prompt.json")),
                agent = agent,
                conversation = readJson(File(dir, "conversation.json")),
                transcript = readTranscript(File(dir, "messages.jsonl")) ?: return@runCatching null,
                reportedTotal = query.reportedTotal,
            )
            ContextBreakdownEstimator.estimate(inputs).toAgentContextJson()
        }.getOrNull()

    /**
     * letta-mobile-57cta: the in-context transcript (compaction summary plus messages) in estimated
     * tokens, unscaled — what letta-code's `compaction_stats.context_tokens_*` measure. Null when
     * the store cannot be read.
     */
    fun transcriptTokens(query: AgentContextQuery): Long? = runCatching {
        val dir = messageReader.contextConversationDir(query.conversationId, query.agentId) ?: return@runCatching null
        val transcript = readTranscript(File(dir, "messages.jsonl")) ?: return@runCatching null
        val summary = transcript.summary?.let { LocalMessageTokens.of(it) } ?: 0
        summary.toLong() + LocalMessageTokens.ofAll(transcript.messages)
    }.getOrNull()

    /** An absent transcript is an empty conversation; one past the byte cap is not estimated at all. */
    private fun readTranscript(file: File): LocalTranscriptContext? {
        if (!file.isFile) return LocalTranscriptContext.read(emptySequence())
        if (file.length() > maxTranscriptBytes) return null
        return file.bufferedReader().use { LocalTranscriptContext.read(it.lineSequence()) }
    }

    private fun readJson(file: File): JsonObject? = runCatching {
        file.takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
    }.getOrNull()
}
