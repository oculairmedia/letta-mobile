package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTailTest {
    private fun file(text: String): File = Files.createTempFile("tail", ".jsonl").toFile().also { it.writeText(text) }

    @Test
    fun theLastLinesComeBackOldestFirst() {
        val lines = (1..10).map { "line-$it" }

        assertEquals(listOf("line-8", "line-9", "line-10"), file(lines.joinToString("\n", postfix = "\n")).tailLines(3, 1_000))
        assertEquals(listOf("line-9", "line-10"), file(lines.joinToString("\n")).tailLines(2, 1_000))
    }

    @Test
    fun blankLinesAreSkippedAndAShortFileComesBackWhole() {
        assertEquals(listOf("a", "b"), file("a\n\n  \nb\n").tailLines(5, 1_000))
    }

    @Test
    fun aLineCutByTheByteWindowIsLeftOut() {
        val text = "first-line-is-long\nsecond\nthird\n"

        assertEquals(listOf("second", "third"), file(text).tailLines(5, "second\nthird\n".length.toLong() + 3))
    }

    @Test
    fun aWindowStartingExactlyAtALineKeepsThatLine() {
        val text = "first\nsecond\nthird\n"

        assertEquals(listOf("second", "third"), file(text).tailLines(5, "second\nthird\n".length.toLong()))
    }

    @Test
    fun linesSpanningReadChunksAndMultiByteTextSurvive() {
        val long = "é".repeat(50_000)
        val lines = listOf("old", long, "x".repeat(70_000), "newest")

        assertEquals(lines.drop(1), file(lines.joinToString("\n", postfix = "\n")).tailLines(3, 10_000_000))
    }

    @Test
    fun aMissingFileHasNoLines() {
        assertEquals(emptyList(), File("/nonexistent/tail.jsonl").tailLines(3, 1_000))
    }
}
