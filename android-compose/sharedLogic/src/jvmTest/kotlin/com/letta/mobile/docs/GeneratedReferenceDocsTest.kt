package com.letta.mobile.docs

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeneratedReferenceDocsTest {
    @Test
    fun checkingAChangedRendererFailsWithoutWritingAndRegenerationRestoresParity() = withDocument { document ->
        val original = "Authored introduction\n$START\nregistry v1\n$END\nAuthored example\n"
        document.writeText(original)
        sync(document, "registry v1", GeneratedReferenceDocs.Mode.Check)

        assertFailsWith<IllegalStateException> { sync(document, "registry v2", GeneratedReferenceDocs.Mode.Check) }
        assertEquals(original, document.readText())
        sync(document, "registry v2", GeneratedReferenceDocs.Mode.Write)
        sync(document, "registry v2", GeneratedReferenceDocs.Mode.Check)
        assertEquals(original.replace("registry v1", "registry v2"), document.readText())
    }

    @Test
    fun repeatedGenerationIsDeterministicAndPreservesAuthoredBytesWithEitherLineEnding() {
        for (newline in listOf("\n", "\r\n")) withDocument { document ->
            val prefix = "\uFEFFAuthored π $newline$newline$START"
            val suffix = "$END${newline}Example with trailing spaces  $newline"
            document.writeText(prefix + newline + "old" + newline + suffix, Charsets.UTF_8)

            sync(document, "new\n\nregistry\n", GeneratedReferenceDocs.Mode.Write)
            val expected = prefix + newline + "new${newline}${newline}registry" + newline + suffix
            assertEquals(expected, document.readText(Charsets.UTF_8))
            val first = document.readBytes()
            sync(document, "new\r\n\r\nregistry\r\n", GeneratedReferenceDocs.Mode.Write)
            assertContentEquals(first, document.readBytes())
            sync(document, "new\n\nregistry", GeneratedReferenceDocs.Mode.Check)
        }
    }

    @Test
    fun malformedMarkersAndNestedGeneratedMarkersFailWithoutOverwriting() = withDocument { document ->
        val malformed = listOf(
            "No markers\n",
            "$START\nold\n$END\n$START\n",
            "$START\nold\n$END\n$END\n",
            "$END\nold\n$START\n",
            "inline $START\nold\n$END\n",
            "$START old\n$END\n",
            "$START\nold $END\n",
            "$START\nold\n$END trailing\n",
        )
        for (original in malformed) {
            document.writeText(original)
            assertFailsWith<IllegalArgumentException> { sync(document, "new", GeneratedReferenceDocs.Mode.Write) }
            assertEquals(original, document.readText())
        }
        val original = "$START\nold\n$END\n"
        document.writeText(original)
        assertFailsWith<IllegalArgumentException> { sync(document, START, GeneratedReferenceDocs.Mode.Write) }
        assertEquals(original, document.readText())
    }

    @Test
    fun onlyExplicitWriteModeCanChangeReferences() {
        assertEquals(GeneratedReferenceDocs.Mode.Check, GeneratedReferenceDocs.configuredMode(null))
        assertEquals(GeneratedReferenceDocs.Mode.Check, GeneratedReferenceDocs.configuredMode("check"))
        assertEquals(GeneratedReferenceDocs.Mode.Write, GeneratedReferenceDocs.configuredMode("write"))
        assertFailsWith<IllegalStateException> { GeneratedReferenceDocs.configuredMode("update") }
    }

    private fun sync(document: File, generated: String, mode: GeneratedReferenceDocs.Mode) =
        GeneratedReferenceDocs.sync(document, MARKERS, generated, mode)

    private fun withDocument(test: (File) -> Unit) {
        val directory = createTempDirectory("generated-reference-test").toFile()
        try {
            test(directory.resolve("reference.md"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val START = "<!-- generated:start -->"
        const val END = "<!-- generated:end -->"
        val MARKERS = GeneratedReferenceDocs.Markers(START, END)
    }
}
