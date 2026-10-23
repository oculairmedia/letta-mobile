package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.GenericToolPresenter
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's MemFS tools: `memory` (create, str_replace, insert, delete, rename,
 * update_description of one entry) and `memory_apply_patch`. Each reads "Wrote memfs <path>"; a
 * replacement opens to only the text that changed, an insert or a new entry to `+ text`, a patch
 * to its diff. The chip opens the entry in the Memory view's files.
 */
object MemfsToolPresenter : ToolPresenter {
    override val id: String = "memfs"

    override val matcher: ToolMatcher = ToolMatcher(listOf(MEMORY, APPLY_PATCH))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val path = pathOf(call)
        return ToolStepLine(
            verb = ToolVerb.WroteMemfs,
            target = path.orEmpty(),
            previews = listOfNotNull(path?.let(ToolPreviewTarget::Memory)),
            failure = MemoryEdits.failure(call),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        if (call.name.equals(APPLY_PATCH, ignoreCase = true)) return call.string(PATCH)?.let(ToolInset::Diff)
        return when (call.string(COMMAND)) {
            "str_replace" -> MemoryEdits.changed(call.string("old_string").orEmpty(), call.string("new_string").orEmpty())
            "insert" -> MemoryEdits.added(call.string("insert_text"))
            "create" -> MemoryEdits.added(call.string("file_text"))
            else -> GenericToolPresenter.argumentsInset(call)
        }
    }

    /**
     * The entry the call writes: its `file_path` (a rename's new path), or the first file a patch
     * updates, adds or deletes; only the patch's header lines are read.
     */
    private fun pathOf(call: ToolCallFacts): String? {
        if (!call.name.equals(APPLY_PATCH, ignoreCase = true)) return PATH_KEYS.firstNotNullOfOrNull(call::string)
        return call.string(PATCH)?.lineSequence()
            ?.firstNotNullOfOrNull { line -> PATCH_HEADERS.firstOrNull(line::startsWith)?.let { line.removePrefix(it).trim() } }
            ?.takeIf { it.isNotEmpty() }
    }

    private const val MEMORY = "memory"
    private const val APPLY_PATCH = "memory_apply_patch"
    private const val COMMAND = "command"
    private const val PATCH = "input"
    private val PATH_KEYS = listOf("new_path", "file_path", "path")
    private val PATCH_HEADERS = listOf("*** Update File:", "*** Add File:", "*** Delete File:")
}
