package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlAutolinkTest {

    @Test
    fun `escapeBareIssueReferences preserves following character after issue reference`() {
        assertEquals(
            "\\#750 likely fixed the stream churn.",
            escapeBareIssueReferences("#750 likely fixed the stream churn."),
        )
        assertEquals(
            "- \\#746 smooth streaming reveal",
            escapeBareIssueReferences("- #746 smooth streaming reveal"),
        )
    }

    @Test
    fun `escapeBareIssueReferences leaves headings and protected spans untouched`() {
        assertEquals("# Heading", escapeBareIssueReferences("# Heading"))
        assertEquals("## Section", escapeBareIssueReferences("## Section"))
        assertEquals("Use `#750` likely", escapeBareIssueReferences("Use `#750` likely"))
        assertEquals("Use ``#750`` literally", escapeBareIssueReferences("Use ``#750`` literally"))
        assertEquals("[#750](https://example.com) likely", escapeBareIssueReferences("[#750](https://example.com) likely"))
        assertEquals("Already \\#750 likely", escapeBareIssueReferences("Already \\#750 likely"))
        assertEquals("https://example.com/#750 likely", escapeBareIssueReferences("https://example.com/#750 likely"))
        assertEquals("Entity &#124; likely", escapeBareIssueReferences("Entity &#124; likely"))
        assertEquals("```kotlin\n#750\n", escapeBareIssueReferences("```kotlin\n#750\n"))
        assertEquals("    #750", escapeBareIssueReferences("    #750"))
    }

    @Test
    fun `autolinkBareUrls wraps bare HTTP URLs in markdown link syntax`() {
        val input = "Check out https://example.com for more info"
        val expected = "Check out [https://example.com](https://example.com) for more info"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls wraps multiple URLs`() {
        val input = "Visit https://example.com and https://test.org"
        val expected = "Visit [https://example.com](https://example.com) and [https://test.org](https://test.org)"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls strips trailing punctuation from URLs`() {
        val input = "Check https://example.com. Also see https://test.org, and (https://another.com)."
        val expected = "Check [https://example.com](https://example.com). Also see [https://test.org](https://test.org), and ([https://another.com](https://another.com))."
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls preserves markdown link syntax`() {
        val input = "See [my link](https://example.com) for details"
        val expected = "See [my link](https://example.com) for details"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls does not linkify URLs inside markdown links`() {
        val input = "[Click here](https://example.com) and also https://test.org"
        val expected = "[Click here](https://example.com) and also [https://test.org](https://test.org)"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls does not linkify URLs inside inline code`() {
        val input = "Use `https://example.com` as the URL"
        val expected = "Use `https://example.com` as the URL"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls does not linkify URLs inside code fences`() {
        val input = """
            Here's some code:
            ```
            curl https://api.example.com/endpoint
            ```
            But this https://example.com should be linked.
        """.trimIndent()
        
        val result = autolinkBareUrls(input)
        
        assert(result.contains("curl https://api.example.com/endpoint")) {
            "URL in code fence should not be linkified"
        }
        assert(result.contains("[https://example.com](https://example.com)")) {
            "URL outside code fence should be linkified"
        }
    }

    @Test
    fun `autolinkBareUrls handles www URLs`() {
        val input = "Visit www.example.com for more"
        val result = autolinkBareUrls(input)
        assert(result.contains("[www.example.com](www.example.com)")) {
            "www URLs should be linkified"
        }
    }

    @Test
    fun `autolinkBareUrls returns original text when no URLs present`() {
        val input = "This is plain text with no URLs"
        val expected = "This is plain text with no URLs"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls handles URL with query params`() {
        val input = "Search at https://example.com/search?q=test&lang=en for results"
        val expected = "Search at [https://example.com/search?q=test&lang=en](https://example.com/search?q=test&lang=en) for results"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls handles URL with hash fragments`() {
        val input = "See https://example.com/docs#section for details"
        val expected = "See [https://example.com/docs#section](https://example.com/docs#section) for details"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls preserves dashed path segments in GitHub PR URLs`() {
        val input = "PR: https://github.com/oculairmedia/letta-mobile/pull/341"
        val expected = "PR: [https://github.com/oculairmedia/letta-mobile/pull/341](https://github.com/oculairmedia/letta-mobile/pull/341)"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls handles mixed content with inline code and bare URLs`() {
        val input = "Use `curl https://api.example.com` but visit https://example.com directly"
        val expected = "Use `curl https://api.example.com` but visit [https://example.com](https://example.com) directly"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls preserves existing markdown and linkifies new URLs`() {
        val input = "Read [the docs](https://docs.example.com) and check https://blog.example.com"
        val expected = "Read [the docs](https://docs.example.com) and check [https://blog.example.com](https://blog.example.com)"
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls handles empty string`() {
        val input = ""
        val expected = ""
        assertEquals(expected, autolinkBareUrls(input))
    }

    @Test
    fun `autolinkBareUrls handles string with only whitespace`() {
        val input = "   \n  \t  "
        val expected = "   \n  \t  "
        assertEquals(expected, autolinkBareUrls(input))
    }
}
