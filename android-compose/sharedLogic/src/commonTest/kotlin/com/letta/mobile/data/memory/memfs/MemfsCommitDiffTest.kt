package com.letta.mobile.data.memory.memfs

import com.letta.mobile.data.diff.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemfsCommitDiffTest {
    private val patch = listOf(
        "diff --git a/system/persona.md b/system/persona.md",
        "index 1111111..2222222 100644",
        "--- a/system/persona.md",
        "+++ b/system/persona.md",
        "@@ -1,3 +1,3 @@",
        " I am Letta.",
        "-I like tea.",
        "+I like coffee.",
        " The end.",
        "diff --git a/notes/new.md b/notes/new.md",
        "new file mode 100644",
        "index 0000000..3333333",
        "--- /dev/null",
        "+++ b/notes/new.md",
        "@@ -0,0 +1,2 @@",
        "+line one",
        "+line two",
        "diff --git a/old.md b/old.md",
        "deleted file mode 100644",
        "index 4444444..0000000",
        "--- a/old.md",
        "+++ /dev/null",
        "@@ -1 +0,0 @@",
        "-gone",
        "diff --git a/profile.png b/profile.png",
        "index 5555555..6666666 100644",
        "Binary files a/profile.png and b/profile.png differ",
        "",
    ).joinToString("\n")

    @Test
    fun splitsOnePatchIntoPerFileDiffs() {
        val files = MemfsCommitDiff.parse(patch)

        assertEquals(listOf("system/persona.md", "notes/new.md", "old.md", "profile.png"), files.map { it.path })
        assertEquals(
            listOf(MemfsFileChange.Modified, MemfsFileChange.Added, MemfsFileChange.Deleted, MemfsFileChange.Binary),
            files.map { it.change },
        )
        assertEquals(listOf(1 to 1, 2 to 0, 0 to 1, 0 to 0), files.map { it.added to it.removed })
    }

    @Test
    fun hunksAreNumberedWithoutGitMetadata() {
        val persona = MemfsCommitDiff.parse(patch).first()
        assertTrue(persona.lines.none { it.kind == DiffLineKind.FileHeader })
        val added = persona.lines.single { it.kind == DiffLineKind.Added }
        assertEquals("I like coffee.", added.text)
        assertEquals(2, added.newLine)
        assertTrue(MemfsCommitDiff.parse(patch).last().lines.isEmpty())
    }

    @Test
    fun anEmptyPatchHasNoFiles() {
        assertTrue(MemfsCommitDiff.parse("").isEmpty())
    }
}
