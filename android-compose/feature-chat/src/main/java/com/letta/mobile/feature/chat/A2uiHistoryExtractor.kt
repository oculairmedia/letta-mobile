package com.letta.mobile.feature.chat

import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiProtocolJson
import com.letta.mobile.data.a2ui.decodeA2uiMessages

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
            val decoded = runCatching {
                decodeA2uiMessages(
                    A2uiProtocolJson.Default,
                    A2uiProtocolJson.Default.parseToJsonElement(body),
                )
            }.getOrNull()
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
