package com.letta.mobile.data.compaction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-57cta: the `conversation.compact` admin_rpc contract shared by the Iroh host that
 * serves it and the clients that call it.
 *
 * Params: `agent_id` (required), `conversation_id` (optional, the agent's `default` when absent),
 * `mode` (optional; the local backend accepts only [CompactionMode.All] and
 * [CompactionMode.SlidingWindow]). The host runs letta-code's `/compact` (`execute_command`), which
 * fires the pre-compact hooks and the compaction-event reflection a manual compact runs anywhere
 * else, and falls back to `conversation_compact` only when the App Server has no such command.
 */
object ConversationCompactRpc {
    const val METHOD: String = "conversation.compact"
    const val AGENT_ID: String = "agent_id"
    const val CONVERSATION_ID: String = "conversation_id"
    const val MODE: String = "mode"
    const val DEFAULT_CONVERSATION: String = "default"
}

/** The compaction modes letta-code's local backend accepts (`validateLocalCompactionSettingsRecord`). */
enum class CompactionMode(val wire: String) {
    All("all"),
    SlidingWindow("sliding_window"),
    ;

    companion object {
        fun fromWire(value: String?): CompactionMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Which App Server command a compaction went through. */
enum class CompactionPath(val wire: String) {
    ExecuteCommand("execute_command"),
    ConversationCompact("conversation_compact"),
}

/**
 * letta-mobile-57cta: what a manual compaction did.
 *
 * [contextTokensBefore] / [contextTokensAfter] are the host's chars/4 estimates of the transcript
 * (summary plus messages) on either side, the same meaning as a streamed `compaction_stats`, so a
 * client corrects its now-stale streamed total exactly as it does after an automatic compaction
 * (a manual compact streams no `usage_statistics` until the next turn). Null when the host has no
 * local-backend store to read.
 */
@Serializable
data class ConversationCompactResult(
    val path: String,
    val summary: String? = null,
    @SerialName("num_messages_before") val messagesBefore: Int? = null,
    @SerialName("num_messages_after") val messagesAfter: Int? = null,
    /** letta-code ran the compaction but the transcript did not shrink ("Already compact"). */
    @SerialName("no_change") val noChange: Boolean = false,
    /** `execute_command`'s text output, kept for display. */
    val output: String? = null,
    @SerialName("context_tokens_before") val contextTokensBefore: Long? = null,
    @SerialName("context_tokens_after") val contextTokensAfter: Long? = null,
)

/**
 * Reads letta-code's `/compact` output (`handleCompactCommand`): "Compaction completed[ (mode: m)].
 * Message buffer length reduced from N to M.\n\nSummary: …", or "Compaction run, but the number of
 * messages is the same". Anything else keeps only the raw [ConversationCompactResult.output].
 */
object CompactCommandOutput {
    private val reduced = Regex("""reduced from (\d+) to (\d+)""")
    private const val SUMMARY_PREFIX = "Summary:"
    private const val NO_CHANGE = "number of messages is the same"

    fun parse(output: String?): ConversationCompactResult {
        val text = output.orEmpty()
        val counts = reduced.find(text)?.destructured?.let { (before, after) -> before.toIntOrNull() to after.toIntOrNull() }
        val summary = text.lineSequence()
            .firstOrNull { it.startsWith(SUMMARY_PREFIX) }
            ?.let { first -> text.substringAfter(first).let { rest -> (first.removePrefix(SUMMARY_PREFIX) + rest).trim() } }
        val before = counts?.first
        val after = counts?.second
        return ConversationCompactResult(
            path = CompactionPath.ExecuteCommand.wire,
            summary = summary?.takeIf { it.isNotEmpty() },
            messagesBefore = before,
            messagesAfter = after,
            noChange = text.contains(NO_CHANGE) || (before != null && before == after),
            output = output,
        )
    }
}
