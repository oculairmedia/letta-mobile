package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolFailure
import com.letta.mobile.data.chat.tools.ToolInset

/**
 * This replacement as only the text that changed: the words both sides share at their start and
 * end are dropped, so a one-word edit of a long sentence reads as that word. Built only for an
 * opened step; nothing when both sides are empty.
 */
internal fun ToolInset.BeforeAfter.changedOnly(): ToolInset? {
    if (before.isEmpty() && after.isEmpty()) return null
    val start = sharedStart()
    val end = sharedEnd()
    val removed = before.substring(start, before.length - end).trim()
    val added = after.substring(start, after.length - end).trim()
    // Text moved but none changed: show the replacement as written.
    return if (removed.isEmpty() && added.isEmpty()) this else ToolInset.BeforeAfter(removed, added)
}

/** Text a call added (an insert, an append, a new entry), read as `+ text`. */
internal fun addedText(text: String): ToolInset = ToolInset.BeforeAfter("", text)

/**
 * A memory call that failed: Letta's memory tools answer `Error: …` (or a Python exception's
 * name). Only the result's start is read.
 */
internal fun ToolCallFacts.memoryFailure(): ToolFailure? {
    val head = result?.trimStart()?.take(ERROR_SCAN_CHARS) ?: return null
    return ToolFailure(null).takeIf { ERROR_PREFIXES.any { head.startsWith(it, ignoreCase = true) } }
}

/** How much both sides share at their start, backed up to the start of the word it ends in. */
private fun ToolInset.BeforeAfter.sharedStart(): Int {
    val max = minOf(before.length, after.length)
    var at = 0
    while (at < max && before[at] == after[at]) at++
    while (at > 0 && !before[at - 1].isWhitespace()) at--
    return at
}

/** How much both sides share at their end, never reaching into the shared start, backed up to a word's end. */
private fun ToolInset.BeforeAfter.sharedEnd(): Int {
    val max = minOf(before.length, after.length) - sharedStart()
    var at = 0
    while (at < max && before[before.length - 1 - at] == after[after.length - 1 - at]) at++
    while (at > 0 && !before[before.length - at].isWhitespace()) at--
    return at
}

private const val ERROR_SCAN_CHARS = 64

private val ERROR_PREFIXES = listOf("Error", "Failed", "ValueError", "KeyError", "Exception")
