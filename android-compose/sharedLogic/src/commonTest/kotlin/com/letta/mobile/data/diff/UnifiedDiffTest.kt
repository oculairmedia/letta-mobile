package com.letta.mobile.data.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedDiffTest {
    private val sample = """
        diff --git a/build.gradle.kts b/build.gradle.kts
        --- a/build.gradle.kts
        +++ b/build.gradle.kts
        @@ -12,4 +12,4 @@ dependencies {
         dependencies {
        -    implementation("foundation:1.10.0")
        +    implementation("foundation:1.11.1")
         }
    """.trimIndent()

    @Test
    fun looksLikeDiffRequiresAHunk() {
        assertTrue(UnifiedDiff.looksLikeDiff(sample))
        assertFalse(UnifiedDiff.looksLikeDiff("just text with a + and - in it"))
    }

    @Test
    fun parsesKindsAndLineNumbers() {
        val lines = UnifiedDiff.parse(sample)
        // Header lines + hunk.
        assertEquals(DiffLineKind.FileHeader, lines[0].kind)
        assertTrue(lines.any { it.kind == DiffLineKind.Hunk })

        val removed = lines.first { it.kind == DiffLineKind.Removed }
        assertEquals("    implementation(\"foundation:1.10.0\")", removed.text)
        assertEquals(13, removed.oldLine)
        assertEquals(null, removed.newLine)

        val added = lines.first { it.kind == DiffLineKind.Added }
        assertEquals(13, added.newLine)
        assertEquals(null, added.oldLine)

        // Context lines keep both line numbers and have the leading space stripped.
        val context = lines.first { it.kind == DiffLineKind.Context }
        assertEquals("dependencies {", context.text)
        assertEquals(12, context.oldLine)
        assertEquals(12, context.newLine)
    }

    @Test
    fun statsCountAddsAndRemoves() {
        val (added, removed) = UnifiedDiff.stats(UnifiedDiff.parse(sample))
        assertEquals(1, added)
        assertEquals(1, removed)
    }
}
