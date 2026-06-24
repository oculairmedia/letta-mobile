package com.letta.mobile.data.composer

/** The two composer autocomplete triggers. */
enum class AutocompleteTrigger(val symbol: Char) {
    /** `/command` — slash commands (only at the start of the composer). */
    Command('/'),

    /** `@mention` — reference a file / agent / memory block anywhere in the text. */
    Mention('@'),
}

/**
 * An in-progress autocomplete token detected in the composer: the [trigger], the
 * typed [query] after the trigger symbol, and the `[start, end)` span it occupies
 * (so a selection can replace exactly that token).
 */
data class ActiveToken(
    val trigger: AutocompleteTrigger,
    val query: String,
    val start: Int,
    val end: Int,
)

/**
 * Platform-neutral composer autocomplete: detects the active `/` or `@` token at
 * the cursor and replaces it on selection.
 *
 * Lives in commonMain so desktop and mobile share one trigger/query/replacement
 * implementation instead of each re-deriving "does this look like a slash command
 * or an @mention" from raw text (letta-mobile: @ mentions live in common).
 *
 * Rules:
 *  - **Command**: the first non-whitespace token starts with `/` and the cursor is
 *    still within that token (no whitespace typed yet). Matches the prior desktop
 *    behavior and avoids firing on mid-sentence paths like "cd /opt".
 *  - **Mention**: the nearest `@` before the cursor that sits at a word boundary
 *    (start of text or after whitespace) with no whitespace between it and the
 *    cursor.
 */
object ComposerAutocomplete {
    fun activeToken(text: String, cursor: Int = text.length): ActiveToken? {
        val end = cursor.coerceIn(0, text.length)
        val upto = text.substring(0, end)

        val firstNonWs = upto.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs >= 0 && upto[firstNonWs] == AutocompleteTrigger.Command.symbol) {
            val rest = upto.substring(firstNonWs + 1)
            if (rest.none { it.isWhitespace() }) {
                return ActiveToken(AutocompleteTrigger.Command, rest, firstNonWs, end)
            }
        }

        var i = end - 1
        while (i >= 0) {
            val c = upto[i]
            if (c.isWhitespace()) break
            if (c == AutocompleteTrigger.Mention.symbol) {
                val atBoundary = i == 0 || upto[i - 1].isWhitespace()
                if (atBoundary) {
                    return ActiveToken(AutocompleteTrigger.Mention, upto.substring(i + 1, end), i, end)
                }
                break
            }
            i--
        }
        return null
    }

    /** Replace [token]'s span in [text] with [replacement] (caller adds any trailing space). */
    fun replaceToken(text: String, token: ActiveToken, replacement: String): String =
        text.substring(0, token.start) + replacement + text.substring(token.end)
}
