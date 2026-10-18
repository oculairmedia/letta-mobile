package com.letta.mobile.data.canvas.compose

import com.letta.mobile.docs.GeneratedReferenceDocs
import java.io.File
import kotlin.test.Test

/**
 * letta-mobile-bglj6.14: docs/reference/canvas-compose-v1.md carries the guide an agent reads
 * (`canvas_compose_guide`) word for word between its markers, so the human copy cannot drift from
 * what the tool answers. The guide is also written to build/canvas-compose-guide.md, so a change
 * to it can be consumed separately. The explicit write task refreshes only the marked body.
 */
class CanvasComposeGuideDocTest {
    @Test
    fun theReferenceDocCarriesTheGuideVerbatim() {
        val guide = CanvasComposeGuide.text
        File("build").resolve("canvas-compose-guide.md").apply { parentFile.mkdirs() }.writeText(guide + "\n")

        GeneratedReferenceDocs.sync(File(DOC), MARKERS, guide)
    }

    private companion object {
        /** From the sharedLogic module directory, where Gradle runs its tests. */
        const val DOC = "../../docs/reference/canvas-compose-v1.md"
        const val START = "<!-- canvas_compose_guide:start -->"
        const val END = "<!-- canvas_compose_guide:end -->"
        val MARKERS = GeneratedReferenceDocs.Markers(START, END)
    }
}
