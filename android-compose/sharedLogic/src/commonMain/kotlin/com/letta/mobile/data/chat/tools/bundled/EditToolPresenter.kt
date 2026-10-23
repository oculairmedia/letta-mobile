package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.diff.UnifiedDiff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * letta-code's `Edit` and `MultiEdit` (and `replace`, `apply_patch`): "Edited src/a.kt +3 −1", the
 * lines it added and removed on the line, opening to the change: the result's unified diff when the
 * tool returned one, else the patch it applied, else what was replaced and what replaced it.
 */
object EditToolPresenter : ToolPresenter {
    override val id: String = "edit"

    override val matcher: ToolMatcher =
        ToolMatcher(listOf("Edit", "MultiEdit", "replace", "edit_file", "apply_patch", "str_replace"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val path = call.filePath() ?: patchedFile(call)
        val counts = changeCounts(call)?.let { (added, removed) -> "+$added −$removed" }
        return ToolStepLine(
            verb = ToolVerb.Edited,
            target = listOfNotNull(path, counts).joinToString(" "),
            previews = filePreview(path),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        val result = call.result
        if (result != null && UnifiedDiff.looksLikeDiff(result)) return ToolInset.Diff(result)
        val patch = call.string(PATCH) ?: return replacement(call)?.let { (before, after) -> ToolInset.BeforeAfter(before, after) }
        return if (UnifiedDiff.looksLikeDiff(patch)) ToolInset.Diff(patch) else ToolInset.Text(patch)
    }

    /**
     * Lines added and removed: counted in the change's diff or patch (`+` / `-` lines, not their
     * headers), else in what was replaced and what replaced it. Null when the call names no change.
     */
    private fun changeCounts(call: ToolCallFacts): Pair<Int, Int>? {
        val diff = call.result?.takeIf(UnifiedDiff::looksLikeDiff) ?: call.string(PATCH)
        if (diff != null) return diffCounts(diff)
        val (before, after) = replacement(call) ?: return null
        return lineCountOrZero(after) to lineCountOrZero(before)
    }

    private fun diffCounts(diff: String): Pair<Int, Int> {
        val lines = diff.lineSequence()
        val added = lines.count { it.startsWith("+") && !it.startsWith("+++") }
        val removed = lines.count { it.startsWith("-") && !it.startsWith("---") }
        return added to removed
    }

    private fun lineCountOrZero(text: String): Int = if (text.isEmpty()) 0 else lineCount(text)

    /** What was replaced and what replaced it: the call's own pair, or every pair of a `MultiEdit`, in order. */
    private fun replacement(call: ToolCallFacts): Pair<String, String>? {
        val edits = (call.arguments?.get(EDITS) as? JsonArray)?.mapNotNull { it as? JsonObject }
        val pairs = edits?.map { it.text(OLD) to it.text(NEW) } ?: listOf(call.string(OLD).orEmpty() to call.string(NEW).orEmpty())
        val before = pairs.joinToString(EDIT_SEPARATOR) { it.first }
        val after = pairs.joinToString(EDIT_SEPARATOR) { it.second }
        return (before to after).takeIf { before.isNotEmpty() || after.isNotEmpty() }
    }

    /** The file an `apply_patch` names on its first `*** Update File:` (or Add/Delete) line. */
    private fun patchedFile(call: ToolCallFacts): String? =
        call.string(PATCH)?.lineSequence()?.firstNotNullOfOrNull { PATCH_FILE.find(it)?.groupValues?.get(1)?.trim() }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private const val OLD = "old_string"
    private const val NEW = "new_string"
    private const val EDITS = "edits"
    private const val PATCH = "input"
    private const val EDIT_SEPARATOR = "\n…\n"
    private val PATCH_FILE = Regex("""^\*\*\* (?:Update|Add|Delete) File: (.+)$""")
}
