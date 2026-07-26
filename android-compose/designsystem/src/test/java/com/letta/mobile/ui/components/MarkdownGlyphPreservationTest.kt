package com.letta.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.loader.content"])
class MarkdownGlyphPreservationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertRendersText(sourceText: String, vararg expectedVisibleText: String) {
        composeTestRule.setContent {
            MarkdownText(text = sourceText)
        }
        expectedVisibleText.forEach { expected ->
            composeTestRule
                .onNodeWithText(expected, substring = true, useUnmergedTree = true)
                .assertExists()
        }
    }

    @Test
    fun `preserves spaces around inline code`() {
        assertRendersText("Press ` Enter ` to continue", " Enter ")
    }

    @Test
    fun `preserves sentence punctuation adjacent to a bare URL`() {
        assertRendersText("Visit https://example.com/.", "Visit https://example.com/.")
    }

    @Test
    fun `preserves parentheses adjacent to a bare URL`() {
        assertRendersText("(See https://example.com/)", "(See https://example.com/)")
    }

    @Test
    fun `preserves escaped markdown chars`() {
        assertRendersText(
            "Use \\* and \\_ for literal asterisks and underscores",
            "Use * and _ for literal asterisks and underscores",
        )
    }

    @Test
    fun `preserves emoji and combining marks`() {
        val familyEmoji =
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val text = "Here is an emoji $familyEmoji and a combining mark: cafe\u0301"

        assertRendersText(text, text)
    }

    @Test
    fun `preserves CJK text adjacent to markdown emphasis`() {
        assertRendersText(
            "\u3053\u308C\u306F **\u91CD\u8981** \u306A\u30C6\u30B9\u30C8\u3067\u3059",
            "\u3053\u308C\u306F \u91CD\u8981 \u306A\u30C6\u30B9\u30C8\u3067\u3059",
        )
    }

    @Test
    fun `preserves inline math delimiters with normal text around them`() {
        assertRendersText(
            "The equation \$E = mc^2\$ is famous",
            "The equation",
            "is famous",
        )
    }
}
