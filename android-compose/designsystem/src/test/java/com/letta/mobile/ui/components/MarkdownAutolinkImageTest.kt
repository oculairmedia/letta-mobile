package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownAutolinkImageTest {

    @Test
    fun `autolinkBareUrls preserves bare URLs and markdown images together`() {
        // Just tests the pure-logic function `autolinkBareUrls`
        val text = "Look at https://example.com and ![alt](https://example.com/img.png)"
        val result = autolinkBareUrls(text)
        assertEquals("Look at [https://example.com](https://example.com) and ![alt](https://example.com/img.png)", result)
    }
}
