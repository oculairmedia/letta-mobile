package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class A2uiMarkdownFallbackTest {

    @Test
    fun `raw a2ui json tag becomes visible fenced code`() {
        val text = """
            Before

            <a2ui-json>
            {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}}
            </a2ui-json>

            After
        """.trimIndent()

        val fallback = exposeA2uiJsonTagsAsCodeFences(text)

        assertFalse(fallback.contains("<a2ui-json>"))
        assertFalse(fallback.contains("</a2ui-json>"))
        assertTrue(fallback.contains("```a2ui-json"))
        assertTrue(fallback.contains("\"createSurface\""))
        assertTrue(fallback.contains("Before"))
        assertTrue(fallback.contains("After"))
    }

    @Test
    fun `fallback is parsed as a code-fence block between prose`() {
        val fallback = exposeA2uiJsonTagsAsCodeFences(
            """
                Before

                <a2ui-json>{"version":"v0.9","deleteSurface":{"surfaceId":"s1"}}</a2ui-json>

                After
            """.trimIndent()
        )

        val blocks = StreamingMarkdownDocumentParser.parse(fallback)

        assertEquals(
            listOf(
                StreamingMarkdownBlockKind.Paragraph,
                StreamingMarkdownBlockKind.CodeFence,
                StreamingMarkdownBlockKind.Paragraph,
            ),
            blocks.map { it.kind },
        )
        assertTrue(blocks[1].source.contains("\"deleteSurface\""))
    }

    @Test
    fun `existing code spans and fences are not rewritten`() {
        val inline = "`<a2ui-json>{}</a2ui-json>`"
        val fenced = """
            ```text
            <a2ui-json>{}</a2ui-json>
            ```
        """.trimIndent()

        assertEquals(inline, exposeA2uiJsonTagsAsCodeFences(inline))
        assertEquals(fenced, exposeA2uiJsonTagsAsCodeFences(fenced))
    }

    @Test
    fun `fallback fence expands when json contains backticks`() {
        val fallback = exposeA2uiJsonTagsAsCodeFences(
            """<a2ui-json>{"text":"```"}</a2ui-json>"""
        )

        assertTrue(fallback.contains("````a2ui-json"))
        assertTrue(fallback.trimEnd().endsWith("````"))
    }
}
