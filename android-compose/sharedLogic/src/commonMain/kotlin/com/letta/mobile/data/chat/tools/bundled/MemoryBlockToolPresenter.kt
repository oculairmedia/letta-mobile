package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import kotlinx.serialization.json.JsonPrimitive

/**
 * Letta's core memory block tools: `memory_replace` / `core_memory_replace` read "Edited memory ·
 * persona" and open to only the text that changed; `memory_insert` / `core_memory_append` read
 * "Added to memory · human" and open to `+ text`; `memory_rethink` reads "Rewrote memory ·
 * persona" and opens to the new value, said to replace the whole block. The chip opens the block
 * in the Memory view.
 */
object MemoryBlockToolPresenter : ToolPresenter {
    override val id: String = "memory-block"

    override val matcher: ToolMatcher = ToolMatcher(REPLACE + ADD + REWRITE)

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val label = call.string(LABEL)
        return ToolStepLine(
            verb = verbOf(call.name),
            target = label.orEmpty(),
            previews = listOfNotNull(label?.let(ToolPreviewTarget::MemoryBlock)),
            failure = MemoryEdits.failure(call),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = when (verbOf(call.name)) {
        ToolVerb.EditedMemory -> MemoryEdits.changed(
            before = call.firstString("old_str", "old_content").orEmpty(),
            after = call.firstString("new_str", "new_content").orEmpty(),
        )
        ToolVerb.AddedToMemory -> MemoryEdits.added(call.firstString("new_str", "content"))
        else -> call.string("new_memory")?.let(ToolInset::Rewrite)
    }

    private fun verbOf(name: String): ToolVerb = when (name.lowercase()) {
        in REPLACE -> ToolVerb.EditedMemory
        in ADD -> ToolVerb.AddedToMemory
        else -> ToolVerb.RewroteMemory
    }

    private const val LABEL = "label"
}

private val REPLACE = listOf("memory_replace", "core_memory_replace")
private val ADD = listOf("memory_insert", "core_memory_append")
private val REWRITE = listOf("memory_rethink")

/** The first of [keys] given as a string, as written: a replacement's whitespace is part of what it changed. */
private fun ToolCallFacts.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> (arguments?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content }
