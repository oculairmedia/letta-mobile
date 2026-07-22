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

    @Test
    fun `standalone streamed list marker remains renderable`() {
        val state = StreamingMarkdownDocumentState()

        val marker = state.update("Intro:\n\n-")
        val completed = state.update("Intro:\n\n- Revoked access")

        assertEquals("Intro:\n\n-", marker.blocks.joinToString("") { it.source })
        assertEquals("Intro:\n\n- Revoked access", completed.blocks.joinToString("") { it.source })
        assertEquals(StreamingMarkdownBlockKind.BulletList, completed.blocks.last().kind)
    }

    @Test
    fun `every streamed prefix reconstructs list heavy response byte for byte`() {
        val response = """
            Subject: Remediation completed for project `gen-lang-client`

            Hello,

            We completed the following containment actions:

            - Removed access to the affected project.
            - Revoked the affected service-account credentials.
            - Stopped Gemini API and Vertex AI workloads.
            - Reviewed IAM membership and local credential references.

            Please confirm whether additional remediation is required.
        """.trimIndent()
        val state = StreamingMarkdownDocumentState()

        response.indices.forEach { endIndex ->
            val prefix = response.substring(0, endIndex + 1)
            val document = state.update(prefix)
            assertEquals(
                prefix,
                document.blocks.joinToString("") { it.source },
                "streamed prefix ending at index $endIndex must remain lossless",
            )
        }
    }
}
