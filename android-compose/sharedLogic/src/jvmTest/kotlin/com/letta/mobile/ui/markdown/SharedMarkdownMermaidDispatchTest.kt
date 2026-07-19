package com.letta.mobile.ui.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedMarkdownMermaidDispatchTest {
    @Test
    fun `mermaid fence dispatches to diagram renderer`() {
        assertEquals(
            CodeFenceRenderer.MermaidDiagram,
            selectCodeFenceRenderer(language = "mermaid", source = "flowchart LR\nA --> B"),
        )
        assertEquals(
            CodeFenceRenderer.MermaidDiagram,
            selectCodeFenceRenderer(language = "MERMAID", source = "sequenceDiagram\nA->>B: hi"),
        )
    }

    @Test
    fun `ordinary and empty mermaid fences stay highlighted`() {
        assertEquals(
            CodeFenceRenderer.HighlightedCode,
            selectCodeFenceRenderer(language = "kotlin", source = "val answer = 42"),
        )
        assertEquals(
            CodeFenceRenderer.HighlightedCode,
            selectCodeFenceRenderer(language = "mermaid", source = "  \n"),
        )
        assertEquals(
            CodeFenceRenderer.HighlightedCode,
            selectCodeFenceRenderer(language = "", source = "plain code"),
        )
    }
}
