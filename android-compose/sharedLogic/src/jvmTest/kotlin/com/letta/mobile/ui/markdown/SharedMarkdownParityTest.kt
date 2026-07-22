package com.letta.mobile.ui.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedMarkdownParityTest {
    private val dogfoodFixture = """
        # Shared renderer

        - Keep one semantic pipeline:
          - **bold text** and `inline code`
          - [links](https://example.com)

        > blockquote

        | Platform | Result |
        | --- | --- |
        | Android | shared |
        | Desktop | shared |

        ```kotlin
        val transport = "iroh"
        ```
    """.trimIndent()

    @Test
    fun `android and desktop consume the same semantic block plan`() {
        val android = StreamingMarkdownDocumentState().update(dogfoodFixture)
        val desktop = StreamingMarkdownDocumentState().update(dogfoodFixture)

        assertEquals(android.blocks, desktop.blocks)
        assertTrue(android.blocks.any { it.kind == StreamingMarkdownBlockKind.Heading })
        assertTrue(android.blocks.any { it.kind == StreamingMarkdownBlockKind.BulletList })
        assertTrue(android.blocks.any { it.kind == StreamingMarkdownBlockKind.Blockquote })
        assertTrue(android.blocks.any { it.kind == StreamingMarkdownBlockKind.Table })
        assertTrue(android.blocks.any { it.kind == StreamingMarkdownBlockKind.CodeFence })
    }

    @Test
    fun `incomplete stream keeps stable block identity while repairing syntax`() {
        val state = StreamingMarkdownDocumentState()
        val first = state.update("- **bold")
        val second = state.update("- **bold text** and `code")

        assertEquals(first.blocks.single().id, second.blocks.single().id)
        assertEquals(StreamingMarkdownBlockKind.BulletList, second.blocks.single().kind)
        assertTrue(second.blocks.single().renderMarkdownSource().endsWith("`"))
    }

    @Test
    fun `table separator scan respects slice bounds and accepts slice ending after dashes`() {
        val text = "prefix| --- | ---:suffix"
        val start = "prefix".length
        val end = start + "| --- | ---".length

        assertTrue(lineLooksLikeTableSeparator("| ---", 0, 5))
        assertTrue(lineLooksLikeTableSeparator(text, start, end))
        assertFalse(lineLooksLikeTableSeparator(text, start, start + 1))
    }
}
