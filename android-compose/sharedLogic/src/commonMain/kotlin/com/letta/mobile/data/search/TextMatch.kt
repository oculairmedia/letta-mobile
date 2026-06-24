package com.letta.mobile.data.search

/**
 * One shared substring-match primitive for every searchable list in the app
 * (model picker, @mention catalog, command palette, …). Keeping it here stops
 * each catalog from re-deriving "case-insensitive contains across these fields".
 */
object TextMatch {
    /** True if [query] is blank, or matches (case-insensitively) any non-null [fields]. */
    fun matches(query: String, vararg fields: String?): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return fields.any { it?.contains(q, ignoreCase = true) == true }
    }
}
