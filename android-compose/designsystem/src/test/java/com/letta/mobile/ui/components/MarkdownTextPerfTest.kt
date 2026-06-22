package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextPerfTest {

    @Test
    fun `containsLikelyInlineMath uses precompiled regex correctly`() {
        assertTrue(containsLikelyInlineMath("Here is some math: $x + y$."))
        assertTrue(containsLikelyInlineMath("$E=mc^2$ is a famous equation."))

        assertFalse(containsLikelyInlineMath("This is just $100 and $200."))
        assertFalse(containsLikelyInlineMath("No math here."))
        assertFalse(containsLikelyInlineMath("x$ abc")) // No whitespace or digits following opening $
    }

    @Test
    fun `autolinkBareUrls strips trailing punctuation correctly`() {
        // autolinkBareUrls calls stripTrailingPunctuation which uses a hoisted set
        val url = "https://example.com"

        // Single trailing punctuation
        assertEquals("Check this out: [$url]($url).", autolinkBareUrls("Check this out: $url."))
        assertEquals("Link [$url]($url), here.", autolinkBareUrls("Link $url, here."))

        // Multiple trailing punctuations
        assertEquals("See ([$url]($url)).", autolinkBareUrls("See ($url)."))
        assertEquals("See ([$url]($url)):", autolinkBareUrls("See ($url):"))

        // No trailing punctuation
        assertEquals("Go to [$url]($url) now.", autolinkBareUrls("Go to $url now."))
    }
}
