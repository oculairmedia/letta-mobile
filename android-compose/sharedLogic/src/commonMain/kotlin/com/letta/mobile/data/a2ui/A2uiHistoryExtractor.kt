package com.letta.mobile.data.a2ui

/**
 * Pulls inline `<a2ui-json>…</a2ui-json>` blocks out of an assistant message's
 * text content and decodes them into [A2uiMessage]s, returning the cleaned
 * text plus the extracted messages. Pure and platform-neutral so the shared
 * chat-timeline projection can run identically on Android and desktop.
 */
internal data class A2uiHistoryExtraction(
    val content: String,
    val messages: List<A2uiMessage>,
)

internal object A2uiHistoryExtractor {
    private val blockRegex = Regex(
        "<a2ui-json\\b[^>]*>([\\s\\S]*?)</a2ui-json>",
        RegexOption.IGNORE_CASE,
    )

    fun extract(content: String): A2uiHistoryExtraction {
        if (!content.contains("<a2ui-json", ignoreCase = true)) {
            return A2uiHistoryExtraction(content = content, messages = emptyList())
        }

        val messages = mutableListOf<A2uiMessage>()
        val stripped = blockRegex.replace(content) { match ->
            val body = match.groups[1]?.value?.trim().orEmpty()
            val decoded = decodeA2uiMessagesLenient(A2uiProtocolJson.Default, body)
            if (decoded == null) {
                match.value
            } else {
                messages += decoded
                ""
            }
        }.trim()

        return A2uiHistoryExtraction(content = stripped, messages = messages)
    }
}
