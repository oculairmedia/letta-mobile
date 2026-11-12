package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

// Every invisible character below is written as a \u escape: this file must pass the rule it tests.
class InvisibleOrBidiCharacterTest {
    private val rule = InvisibleOrBidiCharacter()

    @Test
    fun `every listed code point is reported in strings comments and identifiers`() {
        val codePoints = listOf(0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x2064, 0x2066, 0x2069, 0xFEFF, 0x061C)
        codePoints.forEach { codePoint ->
            val char = Char(codePoint)
            val code = "val a = \"x${char}y\"\n// note $char\nval b${char}c = 1\n"
            assertEquals(3, rule.lint(code).size, "U+%04X".format(codePoint))
        }
    }

    @Test
    fun `the message names the escape to use`() {
        val finding = rule.lint("val a = \"left\u202Eright\"").single()
        assertTrue("'\\u202E'" in finding.message, finding.message)
    }

    @Test
    fun `escapes and ordinary text pass`() {
        val code = "val rtl = \"\\u202E\"\nval joiner = '\\u200D'\nval accent = \"caf\u00E9\"\n"
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `a leading byte order mark passes but one inside the file is reported`() {
        assertEquals(0, rule.lint("\uFEFFval a = 1\n").size)
        assertEquals(1, rule.lint("val a = 1\nval b = \"\uFEFF\"\n").size)
    }

    @Test
    fun `a zero width joiner inside an emoji sequence passes but between letters is reported`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val heart = "\u2764\uFE0F\u200D\uD83D\uDD25"
        assertEquals(0, rule.lint("val family = \"$family\"\nval heart = \"$heart\"\n").size)
        assertEquals(1, rule.lint("val word = \"ab\u200Dcd\"\n").size)
    }

    @Test
    fun `test sources are checked too`(@TempDir root: File) {
        val findings = rule.lintAt(root, "app/src/test/kotlin/A.kt", "val a = \"\u2066x\u2069\"\n")
        assertEquals(2, findings.size)
    }
}
