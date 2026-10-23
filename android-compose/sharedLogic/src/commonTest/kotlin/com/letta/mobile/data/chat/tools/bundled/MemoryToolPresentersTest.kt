package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.steps.ToolActivity
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolFailure
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.steps.activity
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** letta-mobile-2ccpv: how memory block and MemFS edits read, and that only the changed text opens. */
class MemoryToolPresentersTest {
    private val registry = BundledToolPresenters.registry

    private fun call(name: String, arguments: String, result: String? = "OK") = ToolCallFacts(name, arguments, result)

    @Test
    fun aBlockReplacementReadsEditedMemoryAndOpensToOnlyTheWordsThatChanged() {
        val facts = call(
            "memory_replace",
            """{"label":"persona","old_str":"I am calm and I like tea.","new_str":"I am calm and I like coffee."}""",
        )
        val line = registry.line(facts)
        assertEquals(ToolVerb.EditedMemory, line.verb)
        assertEquals("persona", line.target)
        assertEquals(listOf(ToolPreviewTarget.MemoryBlock("persona")), line.previews)
        assertNull(line.failure)
        assertEquals(ToolInset.BeforeAfter("tea.", "coffee."), registry.inset(facts))
    }

    @Test
    fun coreMemoryReplaceReadsTheSameWay() {
        val facts = call("core_memory_replace", """{"label":"human","old_content":"Name: Sam","new_content":"Name: Alex"}""")
        assertEquals(ToolVerb.EditedMemory, registry.line(facts).verb)
        assertEquals(ToolInset.BeforeAfter("Sam", "Alex"), registry.inset(facts))
    }

    @Test
    fun anInsertOrAppendReadsAddedToMemoryAndOpensToTheAddedText() {
        val insert = call("memory_insert", """{"label":"human","new_str":"Likes hiking","insert_line":3}""")
        assertEquals(ToolVerb.AddedToMemory, registry.line(insert).verb)
        assertEquals(ToolInset.BeforeAfter("", "Likes hiking"), registry.inset(insert))
        val append = call("core_memory_append", """{"label":"human","content":"Has a dog"}""")
        assertEquals(ToolVerb.AddedToMemory, registry.line(append).verb)
        assertEquals(ToolInset.BeforeAfter("", "Has a dog"), registry.inset(append))
    }

    @Test
    fun aRethinkReadsRewroteMemoryAndOpensToTheNewValue() {
        val facts = call("memory_rethink", """{"label":"persona","new_memory":"A new persona."}""")
        assertEquals(ToolVerb.RewroteMemory, registry.line(facts).verb)
        assertEquals(ToolInset.Rewrite("A new persona."), registry.inset(facts))
    }

    @Test
    fun aMemfsEditReadsWroteMemfsWithItsPathAndOpensInTheMemoryFiles() {
        val replace = call(
            "memory",
            """{"command":"str_replace","file_path":"system/human/preferences.md",""" +
                """"old_string":"theme: dark","new_string":"theme: light"}""",
        )
        val line = registry.line(replace)
        assertEquals(ToolVerb.WroteMemfs, line.verb)
        assertEquals("system/human/preferences.md", line.target)
        assertEquals(listOf(ToolPreviewTarget.Memory("system/human/preferences.md")), line.previews)
        assertEquals(ToolInset.BeforeAfter("dark", "light"), registry.inset(replace))

        val create = call("memory", """{"command":"create","file_path":"notes.md","file_text":"hello"}""")
        assertEquals(ToolInset.BeforeAfter("", "hello"), registry.inset(create))
        val rename = call("memory", """{"command":"rename","old_path":"a.md","new_path":"b.md"}""")
        assertEquals("b.md", registry.line(rename).target)
    }

    @Test
    fun aMemoryPatchOpensToItsDiffAndNamesTheFileItUpdates() {
        val patch = "*** Begin Patch\n*** Update File: system/human/prefs/coding.md\n@@\n" +
            "-Use broad abstractions\n+Prefer small helpers\n*** End Patch"
        val facts = ToolCallFacts("memory_apply_patch", """{"reason":"r","input":${JsonPrimitive(patch)}}""", "OK")
        assertEquals("system/human/prefs/coding.md", registry.line(facts).target)
        assertIs<ToolInset.Diff>(registry.inset(facts))
    }

    @Test
    fun anErrorResultIsAFailureAndSuccessIsQuiet() {
        val failed = call("memory_replace", """{"label":"persona","old_str":"x","new_str":"y"}""", result = "Error: old_str not found")
        assertEquals(ToolFailure(null), registry.line(failed).failure)
        assertNull(registry.line(call("memory_rethink", """{"label":"p","new_memory":"v"}""")).failure)
    }

    @Test
    fun memoryStepsGroupAsUpdatedMemory() {
        listOf(ToolVerb.EditedMemory, ToolVerb.AddedToMemory, ToolVerb.RewroteMemory, ToolVerb.WroteMemfs)
            .forEach { assertEquals(ToolActivity.Remembered, it.activity) }
    }
}
