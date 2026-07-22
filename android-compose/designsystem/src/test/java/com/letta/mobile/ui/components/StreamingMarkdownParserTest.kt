package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class StreamingMarkdownParserTest {

    @Test
    fun `partitionStreamingMarkdown empty string returns empty`() {
        val partition = partitionStreamingMarkdown("")
        assertEquals(0, partition.committedBlocks.size)
        assertEquals("", partition.activeTail)
    }

    @Test
    fun `deferTrailingBoundaryCommit with active tail retains active tail`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = listOf(MarkdownBlock("k1", "t1")),
            activeTail = "tail"
        )
        val deferred = partition.deferTrailingBoundaryCommit("displayed\n\n")
        assertEquals("tail", deferred.activeTail)
        assertEquals(1, deferred.committedBlocks.size)
    }
    
    @Test
    fun `deferTrailingBoundaryCommit without active tail and no double newline does nothing`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = listOf(MarkdownBlock("k1", "t1")),
            activeTail = ""
        )
        val deferred = partition.deferTrailingBoundaryCommit("displayed")
        assertEquals("", deferred.activeTail)
        assertEquals(1, deferred.committedBlocks.size)
    }

    @Test
    fun `deferTrailingBoundaryCommit with empty blocks does nothing`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = emptyList(),
            activeTail = ""
        )
        val deferred = partition.deferTrailingBoundaryCommit("displayed\n\n")
        assertEquals("", deferred.activeTail)
        assertEquals(0, deferred.committedBlocks.size)
    }

    @Test
    fun `deferTrailingBoundaryCommit moves last block to activeTail when ending with double newline`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = listOf(MarkdownBlock("k1", "t1")),
            activeTail = ""
        )
        val deferred = partition.deferTrailingBoundaryCommit("displayed\n\n")
        assertEquals("t1", deferred.activeTail)
        assertEquals(0, deferred.committedBlocks.size)
    }

    @Test
    fun `looksLikeMarkdownTable handles simple tables`() {
        assertTrue("| a | b |\n| --- | --- |\n".looksLikeMarkdownTable())
        assertFalse("Not a table".looksLikeMarkdownTable())
    }

    @Test
    fun `looksLikeTableRowTail detects incomplete row`() {
        assertTrue("| a | b".looksLikeTableRowTail())
        assertTrue("| a ".looksLikeTableRowTail())
        assertTrue("a | b".looksLikeTableRowTail())
        assertFalse("no pipes here".looksLikeTableRowTail())
    }

    @Test
    fun `ensureTrailingNewline appends newline if missing`() {
        assertEquals("test\n", "test".ensureTrailingNewline())
        assertEquals("test\n", "test\n".ensureTrailingNewline())
    }

    @Test
    fun `lineHasPipe returns true when pipe present`() {
        assertTrue(lineHasPipe("a | b", 0, 5))
        assertFalse(lineHasPipe("a b", 0, 3))
    }

    @Test
    fun `lineLooksLikeTableSeparator correctly identifies separator`() {
        assertTrue(lineLooksLikeTableSeparator("| --- | --- |", 0, 13))
        assertTrue(lineLooksLikeTableSeparator("| :--- | :---: | ---: |", 0, 23))
        assertFalse(lineLooksLikeTableSeparator("---", 0, 3))
        assertFalse(lineLooksLikeTableSeparator("| a | b |", 0, 9))
    }

    @Test
    fun `lineLooksLikeTableSeparator respects separator slice end`() {
        val text = "prefix| --- | ---:suffix"
        val start = "prefix".length
        val end = start + "| --- | ---".length

        assertTrue(lineLooksLikeTableSeparator("| ---", 0, 5))
        assertTrue(lineLooksLikeTableSeparator(text, start, end))
        assertFalse(lineLooksLikeTableSeparator(text, start, start + 1))
    }

    @Test
    fun `containsPipe returns true if text has pipe in range`() {
        assertTrue(containsPipe("a | b", 0, 5))
        assertFalse(containsPipe("a b", 0, 3))
    }

    @Test
    fun `splitMarkdownTableRow handles empty, simple, and escaped pipes`() {
        assertEquals(emptyList<String>(), splitMarkdownTableRow(""))
        assertEquals(listOf("a", "b"), splitMarkdownTableRow("| a | b |"))
        assertEquals(listOf("a | b", "c"), splitMarkdownTableRow("| a \\| b | c |"))
    }

    @Test
    fun `normalizeTableCells pads missing cells`() {
        assertEquals(listOf("a", "b", ""), listOf("a", "b").normalizeTableCells(3))
        assertEquals(listOf("a", "b"), listOf("a", "b").normalizeTableCells(2))
    }

    @Test
    fun `stableMarkdownTableRowKey generates predictable keys`() {
        val key = stableMarkdownTableRowKey(0, listOf("a", "b"))
        assertEquals("row-0-${listOf("a", "b").joinToString(separator = "\u001F").hashCode()}", key) // Hash of "[a, b]" is predictable
    }

    @Test
    fun `parseMarkdownTable works for valid table`() {
        val parsed = parseMarkdownTable("| a | b |\n| --- | --- |\n| 1 | 2 |\n")
        assertEquals(listOf("a", "b"), parsed?.header)
        assertEquals(1, parsed?.rows?.size)
        assertEquals(listOf("1", "2"), parsed?.rows?.first()?.cells)
    }

    @Test
    fun `parseMarkdownTable returns null for invalid table`() {
        val parsed = parseMarkdownTable("not a table")
        assertEquals(null, parsed)
    }

    @Test
    fun `markdownTableColumnWidths computes relative widths`() {
        val widths = markdownTableColumnWidths(
            rows = listOf(listOf("a", "longer header"), listOf("1", "2")),
            columnCount = 2
        )
        // Ensure some width calculation happens
        assertEquals(2, widths.size)
        assertTrue(widths[0] < widths[1])
    }

    @Test
    fun `buildStreamingMarkdownRenderPlan streaming with unclosed fence`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = listOf(MarkdownBlock("k1", "```")),
            activeTail = "code"
        )
        val plan = buildStreamingMarkdownRenderPlan(partition, isStreaming = true)
        // Should defer rendering
        assertEquals(1, plan.committedBlocks.size)
    }

    @Test
    fun `buildStreamingMarkdownRenderPlan non-streaming merges tail to table`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = listOf(MarkdownBlock("k1", "| a | b |\n| --- | --- |\n")),
            activeTail = "| 1 | 2"
        )
        val plan = buildStreamingMarkdownRenderPlan(partition, isStreaming = false)
        assertEquals(1, plan.committedBlocks.size)
        assertEquals("", plan.activeTail)
        assertTrue(plan.committedBlocks[0].text.contains("| 1 | 2\n"))
    }

    @Test
    fun `buildStreamingMarkdownRenderPlan non-streaming non-table keeps tail`() {
        val partition = StreamingMarkdownPartition(
            committedBlocks = listOf(MarkdownBlock("k1", "Intro\n\n")),
            activeTail = "tail"
        )
        val plan = buildStreamingMarkdownRenderPlan(partition, isStreaming = false)
        assertEquals(1, plan.committedBlocks.size)
        assertEquals("tail", plan.activeTail)
    }
}
