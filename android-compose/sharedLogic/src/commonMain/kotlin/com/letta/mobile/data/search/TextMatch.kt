package com.letta.mobile.data.search

/**
 * One shared match primitive for every searchable list in the app (model
 * picker, @mention catalog, command palette, agent rail, …). Keeping it here
 * stops each catalog from re-deriving "case-insensitive match across fields".
 *
 * Matching is TOKEN-based, not whole-query-substring. A literal `contains` on
 * the raw query made punctuated names unfindable: an agent named
 * "PM - letta-mobile" could not be found by typing "pm letta mobile", because
 * the separator and the hyphen are not in the typed text. Users type words, not
 * exact punctuation.
 *
 * Both query and fields are normalized (non-alphanumerics become spaces,
 * lowercased) and every query token must appear in the normalized fields. This
 * is strictly more permissive than the old substring test — anything that
 * matched before still matches.
 */
object TextMatch {
    /** True if [query] is blank, or every token in it matches any non-null [fields]. */
    fun matches(query: String, vararg fields: String?): Boolean {
        if (query.isBlank()) return true
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return true
        // Joined with a space so no token can match across a field boundary —
        // tokens never contain spaces by construction.
        val haystack = fields.filterNotNull().joinToString(separator = " ") { normalize(it) }
        return tokens.all { haystack.contains(it, ignoreCase = true) }
    }

    private fun tokenize(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }

    private fun normalize(text: String): String = buildString(text.length) {
        text.forEach { char ->
            append(if (char.isLetterOrDigit()) char.lowercaseChar() else ' ')
        }
    }
}
