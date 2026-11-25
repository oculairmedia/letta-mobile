package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.context.estimate.ContextBreakdownEstimator
import com.letta.mobile.data.context.estimate.LocalContextInputs
import com.letta.mobile.data.context.estimate.LocalMessageTokens
import com.letta.mobile.data.context.estimate.LocalTranscriptContext
import com.letta.mobile.data.context.estimate.toAgentContextJson
import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Answers held per reader in [LocalBackendContextReader]'s stamped cache. */
internal const val MAX_CACHED_AGENT_CONTEXTS = 64

/** One `agent.context` request. */
data class AgentContextQuery(
    val agentId: String,
    val conversationId: String?,
    /** The client's latest streamed `context_tokens`, to match the sections to. */
    val reportedTotal: Int? = null,
)

/**
 * On-disk `agent.context` reader.
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
    private val maxInlineValueChars: Int = BoundedTranscriptReader.DEFAULT_MAX_INLINE_VALUE_CHARS,
) {

    /**
     * Full parses served by [agentContextProjected] since construction. A repeat whose file
     * stamps are unchanged is served from [contextCache] and does not count, so a test can
     * observe that the bodies were parsed once.
     */
    internal var agentContextFullReads: Int = 0
        private set

    /**
     * The last answers of [agentContextProjected], keyed by (agentId, conversation dir path,
     * reported total) with the fingerprints the stable reads leave on disk. Entry order is
     * access order, so touching an entry saves it from eviction when the cap is reached.
     */
    private val contextCache: LinkedHashMap<Triple<String, String, Int?>, Pair<JsonObject, List<String>>> =
        object : LinkedHashMap<Triple<String, String, Int?>, Pair<JsonObject, List<String>>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                ignored: MutableMap.MutableEntry<Triple<String, String, Int?>, Pair<JsonObject, List<String>>>?,
            ): Boolean = size > MAX_CACHED_AGENT_CONTEXTS
        }

    /**
     * Returns null when the agent is unknown or the store cannot be read, so the caller fails
     * closed rather than serving a hollow context window.
     *
     * A repeat call whose four files (agent record, `system-prompt.json`, `conversation.json`,
     * `messages.jsonl`) still have the same exists/lastModified/length stamps is answered from
     * [contextCache] without re-reading or re-parsing the bodies — the host calls this on every
     * chat switch, and re-parsing was the slowest local read on the live host.
     */
    fun agentContextProjected(query: AgentContextQuery): JsonObject? = runCatching {
        // letta-mobile-by4vm.21: letta.js names records agents/<base64url(id)>.json; bare <id>.json too.
        val agentFile = LocalBackendLayout.findAgentRecordFile(support.baseDir, AgentId(query.agentId)) ?: return@runCatching null
        val dir = messageReader.contextConversationDir(query.conversationId, query.agentId) ?: return@runCatching null
        val key = Triple(query.agentId, dir.path, query.reportedTotal)
        val fingerprint = listOf(agentFile, File(dir, "system-prompt.json"), File(dir, "conversation.json"), File(dir, "messages.jsonl"))
            .map(::stamp)
        synchronized(contextCache) { contextCache[key] }
            ?.takeIf { answer -> answer.second == fingerprint }
            ?.let { answer -> answer.first }
            ?: readFresh(agentFile, dir, query, key, fingerprint)
    }.getOrNull()

    /**
     * The uncached path, unchanged from before the cache existed: parse the agent record, the
     * sidecar, the conversation record and the transcript, then remember the answer (stamped with
     * the fingerprint that produced it) so repeats cost four stat calls instead of the parses.
     * Nulls and failures are not stored.
     */
    private fun readFresh(
        agentFile: File,
        dir: File,
        query: AgentContextQuery,
        key: Triple<String, String, Int?>,
        fingerprint: List<String>,
    ): JsonObject? {
        agentContextFullReads += 1
        val agent = readJson(agentFile) ?: return null
        val inputs = LocalContextInputs(
            systemPromptSidecar = readJson(File(dir, "system-prompt.json")),
            agent = agent,
            conversation = readJson(File(dir, "conversation.json")),
            transcript = readTranscript(File(dir, "messages.jsonl")) ?: return null,
            reportedTotal = query.reportedTotal,
        )
        return ContextBreakdownEstimator.estimate(inputs).toAgentContextJson()
            .also { projected -> synchronized(contextCache) { contextCache[key] = projected to fingerprint } }
    }

    /**
     * The in-context transcript (compaction summary plus messages) in estimated
     * tokens, unscaled — what letta-code's `compaction_stats.context_tokens_*` measure. Null when
     * the store cannot be read.
     */
    fun transcriptTokens(query: AgentContextQuery): Long? = runCatching {
        val dir = messageReader.contextConversationDir(query.conversationId, query.agentId) ?: return@runCatching null
        val transcript = readTranscript(File(dir, "messages.jsonl")) ?: return@runCatching null
        val summary = transcript.summary?.let { LocalMessageTokens.of(it) } ?: 0
        summary.toLong() + LocalMessageTokens.ofAll(transcript.messages)
    }.getOrNull()

    /**
     * An absent transcript is an empty conversation; one past the byte cap is not estimated at all.
     * Below the cap, lines go through [BoundedTranscriptReader]  so a
     * multi-MB base64 image part becomes a length marker instead of one giant String.
     */
    private fun readTranscript(file: File): LocalTranscriptContext? {
        if (!file.isFile) return LocalTranscriptContext.read(emptySequence())
        if (file.length() > maxTranscriptBytes) return null
        return BoundedTranscriptReader.readTranscriptContext(file, maxInlineValueChars)
    }

    /**
     * A look at one file, never a read of it: (path, exists, lastModified, length). Any write that
     * changes what the file holds changes its size or its modification time, so a stamp that is
     * still the same says the cached answer still matches the file on disk.
     */
    private fun stamp(file: File): String = "${file.path}|${file.exists()}|${file.lastModified()}|${file.length()}"

    private fun readJson(file: File): JsonObject? = runCatching {
        file.takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
    }.getOrNull()
}
