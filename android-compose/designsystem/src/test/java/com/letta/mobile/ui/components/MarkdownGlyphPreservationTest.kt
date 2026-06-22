package com.letta.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.loader.content"])
class MarkdownGlyphPreservationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertRendersText(sourceText: String, expectedVisibleText: String) {
        composeTestRule.setContent {
            MarkdownText(text = sourceText, isStreaming = false)
        }
        val treeString = composeTestRule.onRoot().printToString()
        assertTrue(
            "Expected '$expectedVisibleText' to be visible, but tree was:\n$treeString",
            treeString.contains(expectedVisibleText)
        )
    }

    @Test
    fun `preserves spaces around inline code`() {
        assertRendersText("Press ` Enter ` to continue", " Enter ")
    }

    @Test
    fun `preserves punctuation adjacent to bare URLs`() {
        assertRendersText("Visit https://example.com/.", "Visit https://example.com/.")
        assertRendersText("(See https://example.com/)", "(See https://example.com/)")
    }

    @Test
    fun `preserves escaped markdown chars`() {
        assertRendersText("Use \\* and \\_ for literal asterisks and underscores", "Use * and _ for literal asterisks and underscores")
    }

    @Test
    fun `preserves emoji and combining marks`() {
        assertRendersText("Here is an emoji 👨‍👩‍👧‍👦 with combining marks", "Here is an emoji 👨‍👩‍👧‍👦 with combining marks")
    }

    @Test
    fun `preserves CJK text adjacent to markdown emphasis`() {
        assertRendersText("これは **重要** なテストです", "これは 重要 なテストです")
    }

    @Test
    fun `preserves inline math delimiters with normal text around them`() {
        // Rendered via MathBlock, but let's check it doesn't drop the surrounding text
        assertRendersText("The equation \$E = mc^2\$ is famous", "The equation")
        assertRendersText("The equation \$E = mc^2\$ is famous", "is famous")
    }
}
