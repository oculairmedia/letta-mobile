package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the display-math ($$…$$) segmenter that [MarkdownText] uses to
 * splice KaTeX-rendered blocks into the middle of otherwise-Markdown prose.
 */
class MathSplitterTest {

    @Test
    fun `plain text produces a single text segment`() {
        val segs = splitDisplayMathSegments("hello world")
        assertEquals(1, segs.size)
        assertTrue(segs.first() is MathSegment.Text)
        assertEquals("hello world", (segs.first() as MathSegment.Text).content)
    }

    @Test
    fun `single block math is extracted with trimmed body`() {
        val segs = splitDisplayMathSegments("\$\$  E = mc^2  \$\$")
        assertEquals(1, segs.size)
        assertEquals("E = mc^2", (segs.first() as MathSegment.Math).content)
    }

    @Test
    fun `text before and after math`() {
        val segs = splitDisplayMathSegments("before\n\$\$x^2\$\$\nafter")
        assertEquals(3, segs.size)
        assertTrue(segs[0] is MathSegment.Text)
        assertTrue(segs[1] is MathSegment.Math)
        assertTrue(segs[2] is MathSegment.Text)
        assertEquals("x^2", (segs[1] as MathSegment.Math).content)
    }

    @Test
    fun `multi-line math body is preserved`() {
        val d = "\$"
        val src = "${d}${d}\n" +
            "\\int_0^1 x^2 \\, dx\n" +
            "= \\frac{1}{3}\n" +
            "${d}${d}"
        val segs = splitDisplayMathSegments(src)
        assertEquals(1, segs.size)
        val body = (segs.first() as MathSegment.Math).content
        assertTrue(body.contains("\\int_0^1"))
        assertTrue(body.contains("\\frac{1}{3}"))
    }

    @Test
    fun `two math blocks separated by prose`() {
        val segs = splitDisplayMathSegments("A \$\$a\$\$ then B \$\$b\$\$ end")
        val maths = segs.filterIsInstance<MathSegment.Math>().map { it.content }
        assertEquals(listOf("a", "b"), maths)
    }

    @Test
    fun `empty math fences are skipped`() {
        val segs = splitDisplayMathSegments("foo \$\$   \$\$ bar")
        // The pure-whitespace math body should be dropped, but surrounding
        // prose still appears.
        val texts = segs.filterIsInstance<MathSegment.Text>().map { it.content.trim() }
        assertTrue(texts.any { "foo" in it })
    }

    @Test
    fun `no dollars produces single text segment fast path`() {
        val segs = splitDisplayMathSegments("just some prose with \$1 money")
        assertEquals(1, segs.size)
        assertTrue(segs.first() is MathSegment.Text)
    }

    @Test
    fun `buildKatexHtml embeds source as base64 and display mode flag`() {
        val html = buildKatexHtml(
            source = "E = mc^2",
            displayMode = true,
            dark = false,
            backgroundArgb = 0xffffffff.toInt(),
            foregroundArgb = 0xff000000.toInt(),
        )
        // Base64 payload present, katex JS referenced, displayMode wired
        assertTrue(html.contains("katex/katex.min.js"))
        assertTrue(html.contains("katex/katex.min.css"))
        assertTrue(html.contains("displayMode: true"))
        // Base64 of "E = mc^2"
        assertTrue(html.contains("RSA9IG1jXjI="))
    }

    @Test
    fun `buildKatexHtml inline mode toggles displayMode flag`() {
        val html = buildKatexHtml(
            source = "x",
            displayMode = false,
            dark = true,
            backgroundArgb = 0xff101010.toInt(),
            foregroundArgb = 0xfff0f0f0.toInt(),
        )
        assertTrue(html.contains("displayMode: false"))
        assertTrue(html.contains("color-scheme: dark"))
    }

    // ---------- Inline math ($...$) tests ----------

    @Test
    fun `inline math is extracted from surrounding prose`() {
        val segs = splitInlineMathSegments("the formula \$a^2+b^2=c^2\$ works")
        val maths = segs.filterIsInstance<MathSegment.Math>().map { it.content }
        assertEquals(listOf("a^2+b^2=c^2"), maths)
        val texts = segs.filterIsInstance<MathSegment.Text>().map { it.content }
        assertEquals(listOf("the formula ", " works"), texts)
    }

    @Test
    fun `multiple inline math expressions are each extracted`() {
        val segs = splitInlineMathSegments("let \$x\$ and \$y\$ be vars")
        val maths = segs.filterIsInstance<MathSegment.Math>().map { it.content }
        assertEquals(listOf("x", "y"), maths)
    }

    @Test
    fun `currency is not mistaken for inline math`() {
        val segs = splitInlineMathSegments("it costs \$100 and \$200 dollars")
        // No math span should be extracted — `$100` and `$200` start with a
        // digit, which the pattern rejects.
        assertTrue(
            "expected no math segments, got: " +
                segs.filterIsInstance<MathSegment.Math>().map { it.content },
            segs.none { it is MathSegment.Math },
        )
    }

    @Test
    fun `shell-like tokens are not matched when preceded by a word`() {
        // `A$100` — the opening `$` is preceded by a word character, so the
        // lookbehind prevents a match.
        val segs = splitInlineMathSegments("see table A\$100 now")
        assertTrue(segs.none { it is MathSegment.Math })
    }

    @Test
    fun `whitespace-anchored fences are rejected`() {
        // Real LaTeX never opens with whitespace after `\$` or closes with
        // whitespace before `\$`. Reject both forms.
        val segs1 = splitInlineMathSegments("leading \$ x \$ trail")
        assertTrue(segs1.none { it is MathSegment.Math })
        val segs2 = splitInlineMathSegments("trailing \$x \$ trail")
        assertTrue(segs2.none { it is MathSegment.Math })
    }

    @Test
    fun `empty inline math is skipped`() {
        val segs = splitInlineMathSegments("foo \$\$ bar")
        assertTrue(segs.none { it is MathSegment.Math })
    }

    @Test
    fun `multiline inline math is not matched`() {
        // Multi-line math must go through `\$\$…\$\$`, not inline — the pattern
        // forbids newlines inside the body.
        val segs = splitInlineMathSegments("ab \$c\nd\$ ef")
        assertTrue(segs.none { it is MathSegment.Math })
    }

    @Test
    fun `plain prose round-trips through the splitter`() {
        val segs = splitInlineMathSegments("nothing mathy here")
        assertEquals(1, segs.size)
        assertTrue(segs.first() is MathSegment.Text)
        assertEquals("nothing mathy here", (segs.first() as MathSegment.Text).content)
    }

    @Test
    fun `containsLikelyInlineMath precheck mirrors the regex`() {
        assertTrue(containsLikelyInlineMath("hello \$x+y\$ world"))
        // No `\$` at all → fast false.
        assertEquals(false, containsLikelyInlineMath("hello world"))
        // Currency → no match.
        assertEquals(false, containsLikelyInlineMath("\$100 and \$200"))
        // Whitespace-anchored fence → no match.
        assertEquals(false, containsLikelyInlineMath("leading \$ x \$ trail"))
    }
}
