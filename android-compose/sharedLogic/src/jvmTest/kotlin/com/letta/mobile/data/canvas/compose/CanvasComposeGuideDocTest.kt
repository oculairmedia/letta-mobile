package com.letta.mobile.data.canvas.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.14: docs/reference/canvas-compose-v1.md carries the guide an agent reads
 * (`canvas_compose_guide`) word for word between its markers, so the human copy cannot drift from
 * what the tool answers. The guide is also written to build/canvas-compose-guide.md, so a change
 * to it can be pasted into the doc.
 */
class CanvasComposeGuideDocTest {
    @Test
    fun theReferenceDocCarriesTheGuideVerbatim() {
        val guide = CanvasComposeGuide.text
        File("build").resolve("canvas-compose-guide.md").apply { parentFile.mkdirs() }.writeText(guide + "\n")

        val doc = File(DOC).also { assertTrue(it.isFile, "missing ${it.absolutePath}") }.readText().replace("\r\n", "\n")
        assertTrue(START in doc && END in doc, "the doc has lost its guide markers")
        val copy = doc.substringAfter(START).substringBefore(END).trim('\n')
        assertEquals(guide, copy, "docs/reference/canvas-compose-v1.md is out of date: paste build/canvas-compose-guide.md between its markers")
    }

    private companion object {
        /** From the sharedLogic module directory, where Gradle runs its tests. */
        const val DOC = "../../docs/reference/canvas-compose-v1.md"
        const val START = "<!-- canvas_compose_guide:start -->"
        const val END = "<!-- canvas_compose_guide:end -->"
    }
}
