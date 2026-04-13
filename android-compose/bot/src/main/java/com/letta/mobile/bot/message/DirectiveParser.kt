package com.letta.mobile.bot.message

import com.letta.mobile.bot.core.Directive

/**
 * Parses agent response text for embedded directives.
 * Kotlin equivalent of lettabot's directive parsing in the response pipeline.
 *
 * Scans the response text for XML-like directive tags, extracts them,
 * and returns both the clean display text and the parsed directives.
 */
object DirectiveParser {

    data class ParseResult(
        /** The response text with directives stripped out. */
        val cleanText: String,
        /** Parsed directives found in the response. */
        val directives: List<Directive>,
    )

    /** Regex patterns for directive extraction. */
    private val SEND_FILE_REGEX = Regex(
        """<send-file\s+path="([^"]+)"(?:\s+name="([^"]*)")?(?:\s+mime="([^"]*)")?\s*/>""",
        RegexOption.IGNORE_CASE,
    )
    private val NO_REPLY_REGEX = Regex("""<no-reply\s*/>""", RegexOption.IGNORE_CASE)
    private val VOICE_REGEX = Regex("""<voice>(.*?)</voice>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val REACT_REGEX = Regex("""<react\s+emoji="([^"]+)"\s*/>""", RegexOption.IGNORE_CASE)
    private val DELETE_TRIGGER_REGEX = Regex("""<delete-trigger\s*/>""", RegexOption.IGNORE_CASE)
    private val PIN_REGEX = Regex("""<pin\s*/>""", RegexOption.IGNORE_CASE)
    private val ACTIONS_REGEX = Regex("""<actions>(.*?)</actions>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * Parse the agent's raw response text for directives.
     * Returns the clean display text and extracted directives.
     */
    fun parse(responseText: String): ParseResult {
        val directives = mutableListOf<Directive>()
        var text = responseText

        // Parse <send-file .../> directives
        SEND_FILE_REGEX.findAll(text).forEach { match ->
            directives.add(
                Directive.SendFile(
                    path = match.groupValues[1],
                    name = match.groupValues[2].ifEmpty { null },
                    mimeType = match.groupValues[3].ifEmpty { null },
                )
            )
        }
        text = SEND_FILE_REGEX.replace(text, "")

        // Parse <no-reply/>
        if (NO_REPLY_REGEX.containsMatchIn(text)) {
            directives.add(Directive.NoReply)
        }
        text = NO_REPLY_REGEX.replace(text, "")

        // Parse <voice>...</voice>
        VOICE_REGEX.findAll(text).forEach { match ->
            directives.add(Directive.Voice(text = match.groupValues[1].trim()))
        }
        text = VOICE_REGEX.replace(text, "")

        // Parse <actions>...</actions> block and its children
        ACTIONS_REGEX.findAll(text).forEach { match ->
            val actionsBlock = match.groupValues[1]

            REACT_REGEX.findAll(actionsBlock).forEach { reactMatch ->
                directives.add(Directive.React(emoji = reactMatch.groupValues[1]))
            }

            if (DELETE_TRIGGER_REGEX.containsMatchIn(actionsBlock)) {
                directives.add(Directive.DeleteTrigger)
            }

            if (PIN_REGEX.containsMatchIn(actionsBlock)) {
                directives.add(Directive.Pin)
            }
        }
        text = ACTIONS_REGEX.replace(text, "")

        // Also check for standalone react/delete/pin outside <actions>
        REACT_REGEX.findAll(text).forEach { match ->
            val directive = Directive.React(emoji = match.groupValues[1])
            if (directive !in directives) directives.add(directive)
        }
        text = REACT_REGEX.replace(text, "")

        if (DELETE_TRIGGER_REGEX.containsMatchIn(text) && Directive.DeleteTrigger !in directives) {
            directives.add(Directive.DeleteTrigger)
        }
        text = DELETE_TRIGGER_REGEX.replace(text, "")

        if (PIN_REGEX.containsMatchIn(text) && Directive.Pin !in directives) {
            directives.add(Directive.Pin)
        }
        text = PIN_REGEX.replace(text, "")

        return ParseResult(
            cleanText = text.trim(),
            directives = directives,
        )
    }
}
