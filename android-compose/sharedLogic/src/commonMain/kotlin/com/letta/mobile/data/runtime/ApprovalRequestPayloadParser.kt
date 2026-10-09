package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.PermissionSuggestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * letta-mobile-bzvro.11 / .12: reads the optional parts of a `can_use_tool` control request
 * (`permission_suggestions`, `blocked_path`, `diffs`).
 *
 * Every read is FAIL-SOFT: this runs inside the turn collect loop over raw server frames, so a
 * field of an unexpected shape degrades to "absent" and never throws. Unknown fields and
 * entries are ignored, so a newer server's additions cannot break an older client.
 */
internal object ApprovalRequestPayloadParser {
    fun suggestions(request: JsonObject): List<PermissionSuggestion> =
        (request["permission_suggestions"] as? JsonArray).orEmpty().asSequence().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = obj.string("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PermissionSuggestion(id = id.take(MAX_ID_CHARS), text = text.take(MAX_SUGGESTION_CHARS))
        }.take(MAX_SUGGESTIONS).toList()

    fun blockedPath(request: JsonObject): String? =
        request.string("blocked_path")?.takeIf { it.isNotBlank() }?.take(MAX_LABEL_CHARS)

    /**
     * The server's text is untrusted and shown on the UI thread, so it is bounded HERE, once:
     * at most [MAX_DIFFS] previews, each cut to [MAX_DIFF_LINES] lines / [MAX_DIFF_CHARS] chars
     * (with a marker), and paths/notes to [MAX_LABEL_CHARS]. Nothing downstream re-bounds it.
     */
    fun diffs(request: JsonObject): List<ApprovalDiffPreview> =
        (request["diffs"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { (it as? JsonObject)?.toPreview() }
            .take(MAX_DIFFS)
            .toList()

    const val MAX_DIFFS = 20
    const val MAX_DIFF_LINES = 2_000
    const val MAX_DIFF_CHARS = 200_000
    const val MAX_LABEL_CHARS = 1_000
    const val MAX_SUGGESTIONS = 10
    const val MAX_SUGGESTION_CHARS = 500
    const val MAX_ID_CHARS = 200
    const val MAX_TOOL_NAME_CHARS = 200
    private const val MAX_HUNKS = 200
    const val TRUNCATION_MARKER = "... diff truncated"

    /** [text] cut to the line and char budget (any of \n, \r\n, \r separates lines), with a marker when cut. */
    internal fun capDiff(text: String): String {
        val clipped = text.take(MAX_DIFF_CHARS)
        // A trailing newline ends the last line; it does not start one more.
        val lines = clipped.lineSequence().take(MAX_DIFF_LINES + 2).toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        if (text.length <= MAX_DIFF_CHARS && lines.size <= MAX_DIFF_LINES) return text
        return lines.take(MAX_DIFF_LINES).joinToString("\n") + "\n" + TRUNCATION_MARKER
    }

    private fun JsonObject.toPreview(): ApprovalDiffPreview? {
        val path = firstString("fileName", "file_name", "file_path", "path")?.take(MAX_LABEL_CHARS)
        val unified = (firstString("unified_diff", "unifiedDiff", "diff", "patch") ?: hunkText(this["hunks"]))?.let(::capDiff)
        val note = firstString("reason", "message")?.take(MAX_LABEL_CHARS)
        return ApprovalDiffPreview(path = path, unifiedDiff = unified, note = note).takeUnless { it.isEmpty() }
    }

    /** `structuredPatch`-style hunks: `{oldStart, oldLines, newStart, newLines, lines: ["+x", "-y", " z"]}`. */
    private fun hunkText(hunks: JsonElement?): String? {
        val text = (hunks as? JsonArray).orEmpty().asSequence()
            .mapNotNull { (it as? JsonObject)?.hunkLines() }
            .take(MAX_HUNKS)
            .joinToString("\n")
        return text.ifBlank { null }
    }

    private fun JsonObject.hunkLines(): String? {
        val lines = (this["lines"] as? JsonArray)?.asSequence()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.take(MAX_DIFF_LINES + 1)?.toList() ?: return null
        val oldStart = firstInt("oldStart", "old_start") ?: 1
        val newStart = firstInt("newStart", "new_start") ?: 1
        val oldCount = firstInt("oldLines", "old_lines") ?: lines.count { !it.startsWith("+") }
        val newCount = firstInt("newLines", "new_lines") ?: lines.count { !it.startsWith("-") }
        return (listOf("@@ -$oldStart,$oldCount +$newStart,$newCount @@") + lines).joinToString("\n")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { string(it)?.takeIf(String::isNotBlank) }

    private fun JsonObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.intOrNull }
}
