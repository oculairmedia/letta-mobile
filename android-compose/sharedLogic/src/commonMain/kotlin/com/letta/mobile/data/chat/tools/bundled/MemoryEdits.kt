package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolFailure
import com.letta.mobile.data.chat.tools.ToolInset

/** What the memory presenters share: the changed span of a replacement, and a failed memory call. */
internal object MemoryEdits {
    /**
     * [before] -> [after] as only the text that changed: the words both share at their start and
     * end are dropped, so a one-word edit of a long sentence reads as that word. Built only for an
     * opened step.
     */
    fun changed(before: String, after: String): ToolInset? {
        if (before.isEmpty() && after.isEmpty()) return null
        val prefix = wordStart(before, commonPrefix(before, after))
        val suffix = wordEnd(before, commonSuffix(before, after, prefix))
        val removed = before.substring(prefix, before.length - suffix)
        val added = after.substring(prefix, after.length - suffix)
        // Text moved but none changed: show the replacement as written.
        if (removed.isEmpty() && added.isEmpty()) return ToolInset.BeforeAfter(before, after)
        return ToolInset.BeforeAfter(removed.trim(), added.trim())
    }

    /** [text] added (an insert, an append, a new entry): `+ text`. */
    fun added(text: String?): ToolInset? = text?.takeIf { it.isNotEmpty() }?.let { ToolInset.BeforeAfter("", it) }

    /**
     * A memory call that failed: Letta's memory tools answer `Error: …` (or a Python exception's
     * name). Only the result's start is read.
     */
    fun failure(call: ToolCallFacts): ToolFailure? {
        val head = call.result?.trimStart()?.take(ERROR_SCAN_CHARS) ?: return null
        return ToolFailure(null).takeIf { ERROR_PREFIXES.any { head.startsWith(it, ignoreCase = true) } }
    }

    private fun commonPrefix(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    /** The shared end of [a] and [b], never reaching into the shared start [prefix]. */
    private fun commonSuffix(a: String, b: String, prefix: Int): Int {
        val max = minOf(a.length, b.length) - prefix
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /** [prefix] backed up to the start of the word it ends in. */
    private fun wordStart(text: String, prefix: Int): Int {
        var at = prefix
        while (at > 0 && !text[at - 1].isWhitespace()) at--
        return at
    }

    /** [suffix] backed up to the end of the word it starts in. */
    private fun wordEnd(text: String, suffix: Int): Int {
        var at = suffix
        while (at > 0 && !text[text.length - at].isWhitespace()) at--
        return at
    }

    private const val ERROR_SCAN_CHARS = 64

    private val ERROR_PREFIXES = listOf("Error", "Failed", "ValueError", "KeyError", "Exception")
}
