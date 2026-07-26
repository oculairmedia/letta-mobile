package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.loader.content"])
class MarkdownGlyphPreservationTest {
    @Test
    fun `preserves spaces around inline code`() {
        val text = "Press ` Enter ` to continue"

        assertEquals(text, autolinkBareUrls(text))
    }

    @Test
    fun `preserves sentence punctuation adjacent to a bare URL`() {
        assertEquals(
            "Visit [https://example.com/](https://example.com/).",
            autolinkBareUrls("Visit https://example.com/."),
        )
    }

    @Test
    fun `preserves parentheses adjacent to a bare URL`() {
        assertEquals(
            "(See [https://example.com/](https://example.com/))",
            autolinkBareUrls("(See https://example.com/)"),
        )
    }

    @Test
    fun `preserves escaped markdown chars`() {
        val text = "Use \\* and \\_ for literal asterisks and underscores"

        assertEquals(text, autolinkBareUrls(text))
    }

    @Test
    fun `preserves emoji and combining marks`() {
        val familyEmoji =
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val text = "Here is an emoji $familyEmoji and a combining mark: cafe\u0301"

        assertEquals(text, autolinkBareUrls(text))
    }

    @Test
    fun `preserves CJK text adjacent to markdown emphasis`() {
        val text =
            "\u3053\u308C\u306F **\u91CD\u8981** \u306A\u30C6\u30B9\u30C8\u3067\u3059"

        assertEquals(text, autolinkBareUrls(text))
    }

    @Test
    fun `preserves inline math delimiters with normal text around them`() {
        assertEquals(
            listOf(
                MathSegment.Text("The equation "),
                MathSegment.Math("E = mc^2"),
                MathSegment.Text(" is famous"),
            ),
            splitInlineMathSegments("The equation \$E = mc^2\$ is famous"),
        )
    }
}
