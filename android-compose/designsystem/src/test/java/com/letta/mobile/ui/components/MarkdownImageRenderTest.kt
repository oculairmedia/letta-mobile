package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class MarkdownImageRenderTest {

    @Test
    fun `markdown images are parsed into paragraphs not blocks`() {
        val markdown = "Image: ![alt text](https://example.com/image.png) neat"
        val blocks = splitMarkdownBlocks(markdown)

        assertEquals(1, blocks.size)
        assertEquals(markdown, blocks.single().text)
    }

    @Test
    fun `markdown image streaming parsing handles partial image correctly`() {
        val markdown = "Image: ![alt text](https://example.com/image.png)"
        assertEquals(markdown, repairIncompleteMarkdownForStreaming(markdown))

        val partial = "Image: ![alt text]("
        val repaired = repairIncompleteMarkdownForStreaming(partial)
        assertEquals("Image: ", repaired)
    }
}
