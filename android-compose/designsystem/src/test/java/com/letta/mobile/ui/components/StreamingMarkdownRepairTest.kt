package com.letta.mobile.ui.components

import com.letta.mobile.ui.markdown.repairIncompleteMarkdownForStreaming

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class StreamingMarkdownRepairTest {

    @Test
    fun `repairs open a2ui-json tag`() {
        assertEquals(
            "Some text <a2ui-json>{\"foo\"</a2ui-json>",
            repairIncompleteMarkdownForStreaming("Some text <a2ui-json>{\"foo\""),
        )
    }

    @Test
    fun `repairs incomplete bold marker`() {
        assertEquals(
            "This is **bold**",
            repairIncompleteMarkdownForStreaming("This is **bold"),
        )
    }

    @Test
    fun `repairs nested bold and italic markers in close order`() {
        assertEquals(
            "This is **bold and *italic***",
            repairIncompleteMarkdownForStreaming("This is **bold and *italic"),
        )
    }

    @Test
    fun `repairs incomplete inline code span`() {
        assertEquals(
            "Use `code`",
            repairIncompleteMarkdownForStreaming("Use `code"),
        )
    }

    @Test
    fun `repairs incomplete inline code span across active block lines`() {
        assertEquals(
            "the renderer correctly detected the `\nthe a2ui-json tags before the renderer sees them`",
            repairIncompleteMarkdownForStreaming(
                "the renderer correctly detected the `\nthe a2ui-json tags before the renderer sees them",
            ),
        )
    }

    @Test
    fun `repairs incomplete inline code span before later line punctuation`() {
        assertEquals(
            "stream parser strips the `\na2ui-json tags before the renderer sees them." +
                "\nThis sentence should stay visible too.`",
            repairIncompleteMarkdownForStreaming(
                "stream parser strips the `\na2ui-json tags before the renderer sees them.\n" +
                    "This sentence should stay visible too.",
            ),
        )
    }

    @Test
    fun `leaves closed multiline inline code span unchanged`() {
        val markdown = "Use `code\nthat already closed` after it"
        assertEquals(markdown, repairIncompleteMarkdownForStreaming(markdown))
    }

    @Test
    fun `repairs incomplete strikethrough marker`() {
        assertEquals(
            "This is ~~removed~~",
            repairIncompleteMarkdownForStreaming("This is ~~removed"),
        )
    }

    @Test
    fun `leaves partial code fence marker with one backtick unchanged to prevent dropping content`() {
        assertEquals(
            "\n`",
            repairIncompleteMarkdownForStreaming("\n`"),
        )
    }

    @Test
    fun `leaves partial code fence marker with two backticks unchanged to prevent dropping content`() {
        assertEquals(
            "\n``",
            repairIncompleteMarkdownForStreaming("\n``"),
        )
    }

    @Test
    fun `repairs partial code fence marker if followed by text because it is an inline code span`() {
        assertEquals(
            "\n`k`",
            repairIncompleteMarkdownForStreaming("\n`k"),
        )
    }

    @Test
    fun `leaves partial code fence marker with leading spaces unchanged`() {
        assertEquals(
            "\n  ``",
            repairIncompleteMarkdownForStreaming("\n  ``"),
        )
    }

    @Test
    fun `repairs backticks with too many leading spaces as inline code span`() {
        assertEquals(
            "\n    ````",
            repairIncompleteMarkdownForStreaming("\n    ``"),
        )
    }

    @Test
    fun `repairs inline code span correctly if not at start of line`() {
        assertEquals(
            "hello `world`",
            repairIncompleteMarkdownForStreaming("hello `world"),
        )
    }

    @Test
    fun `repairs open fenced code block with a closing fence on a new line`() {
        assertEquals(
            "```kotlin\nval x = 1\n```",
            repairIncompleteMarkdownForStreaming("```kotlin\nval x = 1"),
        )
    }

    @Test
    fun `leaves closed fenced code block unchanged`() {
        val markdown = "```kotlin\nval x = 1\n```"
        assertEquals(markdown, repairIncompleteMarkdownForStreaming(markdown))
    }

    @Test
    fun `repairs open display math fence`() {
        assertEquals(
            "\$\$x^2\$\$",
            repairIncompleteMarkdownForStreaming("\$\$x^2"),
        )
    }

    @Test
    fun `repairs partially emitted display math closing fence`() {
        assertEquals(
            "\$\$x^2\$\$",
            repairIncompleteMarkdownForStreaming("\$\$x^2\$"),
        )
    }

    @Test
    fun `repairs incomplete inline math span`() {
        assertEquals(
            "Let \$x_i\$",
            repairIncompleteMarkdownForStreaming("Let \$x_i"),
        )
    }

    @Test
    fun `repairs simple incomplete inline math variable`() {
        assertEquals(
            "Let \$x\$",
            repairIncompleteMarkdownForStreaming("Let \$x"),
        )
    }

    @Test
    fun `leaves complete inline math unchanged`() {
        val markdown = "Let \$x_i\$ be the index"
        assertEquals(markdown, repairIncompleteMarkdownForStreaming(markdown))
    }

    @Test
    fun `does not treat currency as inline math`() {
        assertEquals(
            "Cost is \$100",
            repairIncompleteMarkdownForStreaming("Cost is \$100"),
        )
    }

    @Test
    fun `does not treat shell variables as inline math`() {
        assertEquals(
            "Use \$PATH",
            repairIncompleteMarkdownForStreaming("Use \$PATH"),
        )
    }

    @Test
    fun `does not treat math-like text inside incomplete inline code as math`() {
        assertEquals(
            "Use `\$x`",
            repairIncompleteMarkdownForStreaming("Use `\$x"),
        )
    }

    @Test
    fun `renders incomplete link text as plain text`() {
        assertEquals(
            "See docs",
            repairIncompleteMarkdownForStreaming("See [docs"),
        )
    }

    @Test
    fun `renders incomplete link destination as plain text`() {
        assertEquals(
            "See docs",
            repairIncompleteMarkdownForStreaming("See [docs](https://example"),
        )
    }

    @Test
    fun `does not rewrite link-like text inside incomplete inline code`() {
        assertEquals(
            "Use `[x`",
            repairIncompleteMarkdownForStreaming("Use `[x"),
        )
    }

    @Test
    fun `leaves complete inline link unchanged`() {
        val markdown = "See [docs](https://example.com)"
        assertEquals(markdown, repairIncompleteMarkdownForStreaming(markdown))
    }

    @Test
    fun `removes incomplete image instead of rendering a broken image`() {
        assertEquals(
            "Image: ",
            repairIncompleteMarkdownForStreaming("Image: ![alt](https://example"),
        )
    }

    @Test
    fun `does not treat multiplication as emphasis`() {
        assertEquals(
            "2 * 3 = 6",
            repairIncompleteMarkdownForStreaming("2 * 3 = 6"),
        )
    }

    @Test
    fun `does not treat word internal underscores as emphasis`() {
        assertEquals(
            "foo_bar",
            repairIncompleteMarkdownForStreaming("foo_bar"),
        )
    }

    @Test
    fun `repairs open a2ui tag even with numbers and acronyms inside`() {
        assertEquals(
            "Some text <a2ui-json>{\"queue\":\"Jules queue\",\"A2UI\":true</a2ui-json>",
            repairIncompleteMarkdownForStreaming("Some text <a2ui-json>{\"queue\":\"Jules queue\",\"A2UI\":true"),
        )
    }

    @Test
    fun `does not lose characters like numbers and acronyms in incomplete inline code`() {
        assertEquals(
            "Here is `A2UI 123 Jules queue`",
            repairIncompleteMarkdownForStreaming("Here is `A2UI 123 Jules queue"),
        )
    }
}
