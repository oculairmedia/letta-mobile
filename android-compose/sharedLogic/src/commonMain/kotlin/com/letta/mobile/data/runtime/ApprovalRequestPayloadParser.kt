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
        (request["permission_suggestions"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = obj.string("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PermissionSuggestion(id = id, text = text)
        }

    fun blockedPath(request: JsonObject): String? =
        request.string("blocked_path")?.takeIf { it.isNotBlank() }

    fun diffs(request: JsonObject): List<ApprovalDiffPreview> =
        (request["diffs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toPreview() }

    private fun JsonObject.toPreview(): ApprovalDiffPreview? {
        val path = firstString("fileName", "file_name", "file_path", "path")
        val unified = firstString("unified_diff", "unifiedDiff", "diff", "patch")
            ?: hunkText(this["hunks"])
        val note = firstString("reason", "message")
        if (path == null && unified == null && note == null) return null
        return ApprovalDiffPreview(path = path, unifiedDiff = unified, note = note)
    }

    /** `structuredPatch`-style hunks: `{oldStart, oldLines, newStart, newLines, lines: ["+x", "-y", " z"]}`. */
    private fun hunkText(hunks: JsonElement?): String? {
        val text = (hunks as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.hunkLines() }
            .joinToString("\n")
        return text.ifBlank { null }
    }

    private fun JsonObject.hunkLines(): String? {
        val lines = (this["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
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
