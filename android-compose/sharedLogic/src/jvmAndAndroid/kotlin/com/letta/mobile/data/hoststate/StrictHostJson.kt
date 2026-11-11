package com.letta.mobile.data.hoststate

import kotlinx.serialization.json.Json

/** Reject duplicate JSON fields before the tree/serialization decoder can discard them. */
internal object StrictHostJson {
    fun requireUniqueKeys(text: String) {
        val scopes = ArrayDeque<JsonScope>()
        var cursor = 0
        while (cursor < text.length) {
            when (text[cursor]) {
                '{' -> { require(scopes.size < MAX_JSON_DEPTH); scopes.addLast(JsonScope(mutableSetOf(), true)); cursor++ }
                '[' -> { require(scopes.size < MAX_JSON_DEPTH); scopes.addLast(JsonScope(null, false)); cursor++ }
                '}', ']' -> { scopes.removeLast(); cursor++ }
                ',' -> { expectNextKey(scopes.lastOrNull()); cursor++ }
                '"' -> cursor = readJsonString(text, cursor, scopes.lastOrNull())
                else -> cursor++
            }
        }
    }

    private fun expectNextKey(scope: JsonScope?) {
        if (scope?.keys != null) scope.expectKey = true
    }

    private fun readJsonString(text: String, start: Int, scope: JsonScope?): Int {
        val end = jsonStringEnd(text, start)
        if (scope?.expectKey == true) {
            val key = Json.decodeFromString<String>(text.substring(start, end))
            require(checkNotNull(scope.keys).add(key)) { "Duplicate host state field" }
            scope.expectKey = false
        }
        return end
    }

    private fun jsonStringEnd(text: String, start: Int): Int {
        var cursor = start + 1
        while (cursor < text.length) {
            if (text[cursor] == '\\') cursor += 2
            else if (text[cursor++] == '"') break
        }
        return cursor
    }

    private class JsonScope(val keys: MutableSet<String>?, var expectKey: Boolean)
    private const val MAX_JSON_DEPTH = 8
}
