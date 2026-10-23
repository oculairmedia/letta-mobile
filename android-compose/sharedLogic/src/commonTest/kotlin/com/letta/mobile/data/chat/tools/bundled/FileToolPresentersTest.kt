package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolVerb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** letta-mobile-2ccpv: how letta-code's file tools read as steps, through the bundled registry. */
class FileToolPresentersTest {
    private val registry = BundledToolPresenters.registry

    private fun call(name: String, arguments: String, result: String? = null) = ToolCallFacts(name, arguments, result)

    @Test
    fun readNamesItsFileAndItsChipOpensTheLinesItRead() {
        val facts = call("Read", """{"file_path":"src/Main.kt","offset":10,"limit":21}""", result = "  10→fun main()")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Read, line.verb)
        assertEquals("src/Main.kt", line.target)
        assertEquals(listOf(ToolPreviewTarget.File("src/Main.kt", 10..30)), line.previews)
        assertEquals(ToolInset.Text("  10→fun main()"), registry.inset(facts))
        assertEquals(listOf(ToolPreviewTarget.File("a.kt")), registry.line(call("read_file", """{"file_path":"a.kt"}""")).previews)
    }

    @Test
    fun writeShowsWhatItWroteAsAdded() {
        val facts = call("Write", """{"file_path":"notes.md","content":"# Notes"}""")
        assertEquals(ToolVerb.Wrote, registry.line(facts).verb)
        assertEquals(ToolInset.BeforeAfter("", "# Notes"), registry.inset(facts))
    }

    @Test
    fun editShowsTheResultsDiffElseWhatItReplaced() {
        val diff = "@@ -1,1 +1,1 @@\n-a\n+b"
        assertEquals(ToolInset.Diff(diff), registry.inset(call("Edit", """{"file_path":"a.kt","old_string":"a","new_string":"b"}""", diff)))
        val plain = registry.inset(call("Edit", """{"file_path":"a.kt","old_string":"a","new_string":"b"}""", "The file was updated"))
        assertEquals(ToolInset.BeforeAfter("a", "b"), plain)
        assertEquals(ToolVerb.Edited, registry.line(call("Edit", """{"file_path":"a.kt"}""")).verb)
    }

    @Test
    fun multiEditJoinsItsEditsInOrder() {
        val edits = """[{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d"}]"""
        val facts = call("MultiEdit", """{"file_path":"a.kt","edits":$edits}""")
        val inset = assertIs<ToolInset.BeforeAfter>(registry.inset(facts))
        assertEquals("a\n…\nc", inset.before)
        assertEquals("b\n…\nd", inset.after)
    }

    @Test
    fun applyPatchNamesTheFileItPatches() {
        val patch = "*** Begin Patch\n*** Update File: src/a.kt\n@@\n-x\n+y\n*** End Patch"
        val facts = call("apply_patch", """{"input":${kotlinx.serialization.json.JsonPrimitive(patch)}}""")
        val line = registry.line(facts)
        assertEquals("src/a.kt", line.target)
        assertEquals(listOf(ToolPreviewTarget.File("src/a.kt")), line.previews)
        assertEquals(ToolInset.Text(patch), registry.inset(facts))
    }

    @Test
    fun globAndGrepReadAsSearchesInTheirRoot() {
        val glob = registry.line(call("Glob", """{"pattern":"**/*.kt","path":"src"}"""))
        assertEquals(ToolVerb.Searched, glob.verb)
        assertEquals("**/*.kt in src", glob.target)
        assertEquals("\"TODO\"", registry.line(call("Grep", """{"pattern":"TODO"}""")).target)
        assertEquals(ToolInset.Text("src/a.kt:3: TODO"), registry.inset(call("Grep", """{"pattern":"TODO"}""", "src/a.kt:3: TODO")))
        assertNull(registry.inset(call("Grep", """{"pattern":"TODO"}""", "")))
    }
}
