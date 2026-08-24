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
    fun `ordinary and empty mermaid fences stay code rendered`() {
        assertEquals(
            CodeFenceRenderer.Code,
            selectCodeFenceRenderer(language = "kotlin", source = "val answer = 42"),
        )
        assertEquals(
            CodeFenceRenderer.Code,
            selectCodeFenceRenderer(language = "mermaid", source = "  \n"),
        )
        assertEquals(
            CodeFenceRenderer.Code,
            selectCodeFenceRenderer(language = "", source = "plain code"),
        )
        assertEquals(
            CodeFenceRenderer.Code,
            selectCodeFenceRenderer(
                language = "mermaid",
                source = "flowchart LR\nA --> B",
                deferIncompleteMermaid = true,
            ),
        )
    }
}
