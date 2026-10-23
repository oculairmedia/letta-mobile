package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolVerb
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** letta-mobile-2ccpv: how letta-code's file tools read as steps, as the owner-approved mockups draw them. */
class FileToolPresentersTest {
    private val registry = BundledToolPresenters.registry

    private fun call(name: String, arguments: String, result: String? = null) = ToolCallFacts(name, arguments, result)

    private fun args(vararg pairs: Pair<String, String>) =
        pairs.joinToString(",", "{", "}") { (key, value) -> "\"$key\":${JsonPrimitive(value)}" }

    @Test
    fun readIsItsChipAtTheLinesItReadAndNoInset() {
        val facts = call("Read", """{"file_path":"src/Main.kt","offset":10,"limit":21}""", result = "  10→fun main()")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Read, line.verb)
        assertEquals("src/Main.kt", line.target)
        assertEquals(listOf(ToolPreviewTarget.File("src/Main.kt", 10..30)), line.previews)
        assertNull(registry.inset(facts))
    }

    @Test
    fun writeSaysHowManyLinesAndShowsOnlyItsFirstLinesNumbered() {
        val content = (1..9).joinToString("\n") { "line $it" }
        val facts = call("Write", args("file_path" to "notes.md", "content" to content))
        assertEquals("notes.md · 9 lines", registry.line(facts).target)
        val inset = assertIs<ToolInset.Text>(registry.inset(facts))
        assertEquals("1  line 1\n2  line 2\n3  line 3\n4  line 4\n5  line 5\n6  line 6\n… 3 more lines", inset.text)
        assertEquals("1  only", (registry.inset(call("Write", args("file_path" to "a", "content" to "only"))) as ToolInset.Text).text)
    }

    @Test
    fun editCountsTheLinesItAddedAndRemoved() {
        val diff = "--- a/a.kt\n+++ b/a.kt\n@@ -1,2 +1,3 @@\n-a\n+b\n+c\n+d"
        val withDiff = call("Edit", args("file_path" to "a.kt", "old_string" to "a", "new_string" to "b"), diff)
        assertEquals("a.kt +3 −1", registry.line(withDiff).target)
        assertEquals(ToolInset.Diff(diff), registry.inset(withDiff))
        val replaced = call("Edit", args("file_path" to "a.kt", "old_string" to "a\nb", "new_string" to "c"), "The file was updated")
        assertEquals("a.kt +1 −2", registry.line(replaced).target)
        assertEquals(ToolInset.BeforeAfter("a\nb", "c"), registry.inset(replaced))
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
    fun applyPatchNamesTheFileItPatchesAndCountsItsLines() {
        val patch = "*** Begin Patch\n*** Update File: src/a.kt\n@@\n-x\n+y\n*** End Patch"
        val facts = call("apply_patch", args("input" to patch))
        val line = registry.line(facts)
        assertEquals("src/a.kt +1 −1", line.target)
        assertEquals(listOf(ToolPreviewTarget.File("src/a.kt")), line.previews)
        assertEquals(ToolInset.Text(patch), registry.inset(facts))
    }

    @Test
    fun globIsAChipPerFileFoundAndSaysHowMany() {
        val facts = call("Glob", """{"pattern":"**/*.kt","path":"src"}""", "src/a.kt\nsrc/b.kt\n")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Searched, line.verb)
        assertEquals("**/*.kt in src · 2 files", line.target)
        assertEquals(listOf(ToolPreviewTarget.File("src/a.kt"), ToolPreviewTarget.File("src/b.kt")), line.previews)
        assertNull(registry.inset(facts))
    }

    @Test
    fun grepGroupsItsMatchesByFileWithLineNumbersAndAChipPerMatch() {
        val facts = call("Grep", """{"pattern":"TODO","path":"src"}""", "src/a.kt:3:// TODO one\nsrc/a.kt:12:// TODO two\nsrc/b.kt:1:TODO")
        val line = registry.line(facts)
        assertEquals("\"TODO\" in src · 3 matches", line.target)
        assertEquals(ToolPreviewTarget.File("src/a.kt", 12..12), line.previews[1])
        assertEquals(ToolInset.Text("src/a.kt\n   3  // TODO one\n  12  // TODO two\nsrc/b.kt\n   1  TODO"), registry.inset(facts))
    }

    @Test
    fun grepListingOnlyFilesReadsAsThoseFiles() {
        val facts = call("Grep", """{"pattern":"TODO"}""", "src/a.kt\nsrc/b.kt")
        assertEquals(listOf(ToolPreviewTarget.File("src/a.kt"), ToolPreviewTarget.File("src/b.kt")), registry.line(facts).previews)
        assertNull(registry.inset(call("Grep", """{"pattern":"TODO"}""", "")))
    }
}
