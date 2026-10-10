package com.letta.mobile.data.chat.projection.meridian

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// letta-mobile-jna0o.6: the text helpers the meridian recogniser reads shell scripts and CLI
// output with. All lenient, none throws.

/** This text as a JSON object, read leniently (tool-call arguments); null when it is not one. */
internal fun String.toJsonObjectOrNull(): JsonObject? = parseWith(LenientJson)

/** This text as a strict JSON object (CLI input and output); null when it is not one. */
internal fun String.toStrictJsonObjectOrNull(): JsonObject? = parseWith(StrictJson)

private fun String.parseWith(json: Json): JsonObject? = try {
    json.parseToJsonElement(trim()) as? JsonObject
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** Whether this word names a shell (`bash`, `/bin/sh`, …). */
internal fun String.isShellProgram(): Boolean = substringAfterLast('/') in SHELLS

/** Whether this word is a shell's run-a-script flag (`-c`, `-lc`, `-ec`). */
internal fun String.isShellScriptFlag(): Boolean = SCRIPT_FLAG.matches(this)

/** This word single-quoted when it needs it, so an argv re-reads as the same words. */
internal fun String.shellQuoted(): String = if (SAFE_WORD.matches(this)) this else "'" + replace("'", "'\\''") + "'"

/** An argv's words as one script. */
internal fun List<String>.asShellScript(): String = joinToString(" ") { it.shellQuoted() }

/** The string elements of a JSON array argv. */
internal fun List<JsonElement>.argvWords(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }

/**
 * The first JSON object in this CLI output that starts a line, matched brace for brace (so trailing
 * shell framing, braces included, is left out); null when there is none.
 */
internal fun String.firstJsonObject(): String? =
    indices.asSequence()
        .filter { this[it] == '{' && (it == 0 || this[it - 1] == '\n') }
        .take(MAX_OBJECT_PROBES)
        .mapNotNull { start -> BraceMatcher(this).objectFrom(start) }
        .firstOrNull { it.toStrictJsonObjectOrNull() != null }

/** Finds where a JSON object closes, skipping braces inside strings. */
private class BraceMatcher(private val text: String) {
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun objectFrom(start: Int): String? {
        for (index in start until text.length) {
            if (closes(text[index])) return text.substring(start, index + 1)
        }
        return null
    }

    /** Reads one character; true when it closes the object. */
    private fun closes(c: Char): Boolean {
        if (inString) {
            readInString(c)
            return false
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> depth--
        }
        return c == '}' && depth == 0
    }

    private fun readInString(c: Char) {
        when {
            escaped -> escaped = false
            c == '\\' -> escaped = true
            c == '"' -> inString = false
        }
    }
}

private const val MAX_OBJECT_PROBES = 4
private val SHELLS = setOf("bash", "sh", "zsh", "dash")
private val SCRIPT_FLAG = Regex("-[a-z]*c[a-z]*")
private val SAFE_WORD = Regex("[A-Za-z0-9_./=:,@%+-]+")
private val LenientJson = Json { isLenient = true }
private val StrictJson = Json
