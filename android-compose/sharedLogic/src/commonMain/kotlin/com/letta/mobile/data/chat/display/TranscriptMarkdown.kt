package com.letta.mobile.data.chat.display

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall

/** What a transcript is made of: the chat's title, who answered, the messages, and what to show. */
data class TranscriptInput(
    val title: String,
    val agentName: String,
    val messages: List<UiMessage>,
    val options: ChatDisplayOptions = ChatDisplayOptions.Default,
)

/**
 * letta-mobile-zxk2n.5 (G91): a conversation as a Markdown document, for "Download transcript".
 * Desktop saves it to a file; Android hands it to the share sheet. It follows the chat's display
 * options, so reasoning and compaction summaries are left out when the timeline hides them.
 */
object TranscriptMarkdown {
    private const val USER_HEADING = "You"
    private const val FENCE = "```"

    fun build(input: TranscriptInput): String = buildString {
        append("# ").append(input.title.ifBlank { "Conversation" }.oneLine()).append("\n")
        input.options.visible(input.messages)
            .filterNot { it.isPending || it.isSendFailed }
            .forEach { message -> appendMessage(message, input.agentName) }
    }.trimEnd() + "\n"

    /** A file name for [title]: letters, digits, dashes and underscores only, ending in `.md`. */
    fun fileName(title: String): String {
        val slug = title.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(MAX_SLUG)
            .trimEnd('-')
        return "${slug.ifEmpty { "transcript" }}.md"
    }

    private fun StringBuilder.appendMessage(message: UiMessage, agentName: String) {
        when {
            message.isCompaction -> section("Conversation compacted", message.content.quoted())
            message.isReasoning -> section("${agentName.oneLine()} (thinking)", message.content.quoted())
            message.role.equals("user", ignoreCase = true) -> section(USER_HEADING, message.content.trim())
            else -> appendAssistant(message, agentName)
        }
    }

    private fun StringBuilder.appendAssistant(message: UiMessage, agentName: String) {
        val calls = message.toolCalls.orEmpty()
        val body = buildList {
            message.content.trim().takeIf { it.isNotEmpty() }?.let(::add)
            calls.forEach { add(it.markdown()) }
        }
        if (body.isNotEmpty()) section(agentName.ifBlank { "Agent" }.oneLine(), body.joinToString("\n\n"))
    }

    private fun StringBuilder.section(heading: String, body: String) {
        append("\n## ").append(heading).append("\n\n").append(body).append("\n")
    }

    private fun UiToolCall.markdown(): String = buildString {
        append("**Tool:** `").append(name.replace("`", "'")).append("`")
        arguments.trim().takeIf { it.isNotEmpty() && it != "{}" }?.let { append("\n\n").append(fenced(it)) }
        result?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n\nResult:\n\n").append(fenced(it)) }
    }

    /** A code block whose fence is longer than any backtick run inside [text], so it cannot break out. */
    private fun fenced(text: String): String {
        val longestRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = if (longestRun >= FENCE.length) "`".repeat(longestRun + 1) else FENCE
        return "$fence\n$text\n$fence"
    }

    private fun String.quoted(): String = trim().lines().joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }

    private fun String.oneLine(): String = lines().joinToString(" ") { it.trim() }.trim()

    private const val MAX_SLUG = 60
}
