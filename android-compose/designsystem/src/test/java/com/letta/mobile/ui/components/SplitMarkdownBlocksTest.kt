package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for [splitMarkdownBlocks] — the per-block prefix splitter that
 * lets [StreamingMarkdownText] render a streaming markdown prefix as
 * independently-keyed blocks (letta-mobile-flk.1).
 *
 * The critical property the splitter must guarantee for the flicker fix
 * to work: **for any two prefixes P1 ⊑ P2 (P1 is a prefix-advance step
 * of P2), the first N blocks of split(P2) must equal the first N blocks
 * of split(P1) by both `text` and `key`.** That stability is what lets
 * Compose skip-recompose unchanged blocks during streaming.
 */
@Tag("unit")
class SplitMarkdownBlocksTest {

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList<MarkdownBlock>(), splitMarkdownBlocks(""))
    }

    @Test
    fun `single paragraph (no terminator) returns one block`() {
        val blocks = splitMarkdownBlocks("Hello world")
        assertEquals(1, blocks.size)
        assertEquals("Hello world", blocks[0].text)
    }

    @Test
    fun `paragraph with terminator returns one block including the terminator`() {
        val blocks = splitMarkdownBlocks("Hello world\n\n")
        assertEquals(1, blocks.size)
        assertEquals("Hello world\n\n", blocks[0].text)
    }

    @Test
    fun `two paragraphs split into two blocks, terminator stays with first`() {
        val blocks = splitMarkdownBlocks("First paragraph.\n\nSecond paragraph.")
        assertEquals(2, blocks.size)
        assertEquals("First paragraph.\n\n", blocks[0].text)
        assertEquals("Second paragraph.", blocks[1].text)
    }

    @Test
    fun `extra blank lines between paragraphs collapse into the first block's terminator`() {
        val blocks = splitMarkdownBlocks("A\n\n\n\nB")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].text.startsWith("A"))
        assertEquals("B", blocks[1].text)
    }

    @Test
    fun `closed code fence stays as one block even with internal blank line`() {
        // The blank line inside the fence is code formatting, NOT a
        // paragraph break. Splitting it would shred the fence.
        val text = "Intro\n\n```python\ndef foo():\n\n    return 1\n```\n\nOutro"
        val blocks = splitMarkdownBlocks(text)
        assertEquals(3, blocks.size)
        assertEquals("Intro\n\n", blocks[0].text)
        assertTrue(
            "fence block must contain the entire fenced span",
            blocks[1].text.startsWith("```python") &&
                blocks[1].text.contains("\n\n    return 1\n") &&
                blocks[1].text.contains("```"),
        )
        assertEquals("Outro", blocks[2].text)
    }

    @Test
    fun `closed display-math fence stays as one block`() {
        val text = "Look:\n\n\$\$x^2 + y^2 = z^2\$\$\n\nDone"
        val blocks = splitMarkdownBlocks(text)
        assertEquals(3, blocks.size)
        assertEquals("Look:\n\n", blocks[0].text)
        assertTrue(blocks[1].text.contains("\$\$x^2 + y^2 = z^2\$\$"))
        assertEquals("Done", blocks[2].text)
    }

    @Test
    fun `complete GFM table closed by prose is one block`() {
        // Header + separator + 2 data rows + non-pipe prose line.
        val text = "Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\nNext paragraph."
        val blocks = splitMarkdownBlocks(text)
        // 3 blocks: intro paragraph, table, prose paragraph.
        assertEquals(3, blocks.size)
        assertEquals("Intro\n\n", blocks[0].text)
        assertEquals("| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n", blocks[1].text)
        assertEquals("Next paragraph.", blocks[2].text)
    }

    @Test
    fun `complete GFM table followed by paragraph break stays one block`() {
        val text = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter"
        val blocks = splitMarkdownBlocks(text)
        // 2 blocks: the table (terminated by \n\n), and "After".
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].text.startsWith("| a |"))
        assertTrue(blocks[0].text.endsWith("\n\n"))
        assertEquals("After", blocks[1].text)
    }

    @Test
    fun `prose with stray pipes does not become a table block`() {
        // Pipes without a separator row → must be treated as plain prose.
        val text = "Choose: yes | no | maybe\n\nNext"
        val blocks = splitMarkdownBlocks(text)
        assertEquals(2, blocks.size)
        assertEquals("Choose: yes | no | maybe\n\n", blocks[0].text)
        assertEquals("Next", blocks[1].text)
    }

    // ─── stability under append (the actual flicker-fix property) ────

    @Test
    fun `appending a new paragraph does not change keys of earlier blocks`() {
        val p1 = "First.\n\nSecond.\n\n"
        val p2 = "First.\n\nSecond.\n\nThird."
        val b1 = splitMarkdownBlocks(p1)
        val b2 = splitMarkdownBlocks(p2)

        // b2's first two blocks must match b1 exactly — same text AND
        // same key. That's what lets Compose skip-recompose them.
        assertEquals(b1.size, 2)
        assertEquals(b2.size, 3)
        for (i in 0 until b1.size) {
            assertEquals("block $i text mismatch", b1[i].text, b2[i].text)
            assertEquals("block $i KEY mismatch (would force recompose)",
                b1[i].key, b2[i].key)
        }
    }

    @Test
    fun `progressive paragraph append preserves earlier keys throughout the stream`() {
        // Simulate the streaming sequence: prefix grows paragraph by
        // paragraph. Keys of already-committed blocks must NEVER change.
        val steps = listOf(
            "Para 1.\n\n",
            "Para 1.\n\nPara 2.\n\n",
            "Para 1.\n\nPara 2.\n\nPara 3.\n\n",
            "Para 1.\n\nPara 2.\n\nPara 3.\n\nPara 4.\n\n",
        )
        val splits = steps.map { splitMarkdownBlocks(it) }
        // For each step, all previously-committed blocks must match
        // the previous step's blocks by (text, key).
        for (s in 1 until splits.size) {
            val prev = splits[s - 1]
            val curr = splits[s]
            assertTrue("prefix step ${s} should have more blocks", curr.size > prev.size)
            for (i in prev.indices) {
                assertEquals("step ${s} block ${i} text drift", prev[i].text, curr[i].text)
                assertEquals("step ${s} block ${i} KEY drift", prev[i].key, curr[i].key)
            }
        }
    }

    @Test
    fun `appending a code fence after paragraphs preserves paragraph keys`() {
        val before = "Intro.\n\nMore prose.\n\n"
        val after = "Intro.\n\nMore prose.\n\n```kotlin\nfun foo() = 1\n```\n\n"
        val b1 = splitMarkdownBlocks(before)
        val b2 = splitMarkdownBlocks(after)
        assertEquals(2, b1.size)
        assertEquals(3, b2.size)
        // First two blocks (the prose paragraphs) must be byte-and-key
        // identical — only the new fence block is added.
        assertEquals(b1[0].key, b2[0].key)
        assertEquals(b1[1].key, b2[1].key)
        assertTrue(b2[2].text.contains("```kotlin"))
    }

    @Test
    fun `appending rows to an in-progress table does not split the committed rows`() {
        // The boundary detector commits at row terminators, so each
        // intermediate prefix passed here is row-aligned. The splitter
        // must coalesce all committed rows into a single table block;
        // adding a new row should grow the table block, not split it.
        val r1 = "| a | b |\n| --- | --- |\n| 1 | 2 |\n"
        val r2 = "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n"
        val b1 = splitMarkdownBlocks(r1)
        val b2 = splitMarkdownBlocks(r2)
        assertEquals(1, b1.size)
        assertEquals(1, b2.size)
        // The single table block grew — its key should change (the
        // table's content changed), but no spurious additional blocks
        // appeared.
        assertNotEquals(b1[0].key, b2[0].key)
        assertTrue(b2[0].text.contains("| 3 | 4 |"))
    }

    @Test
    fun `block keys encode index so reordered identical paragraphs are distinguishable`() {
        val text = "Same.\n\nSame.\n\nSame."
        val blocks = splitMarkdownBlocks(text)
        assertEquals(3, blocks.size)
        // All three blocks have identical text but DIFFERENT keys —
        // index is part of the key, so Compose treats them as distinct
        // slots and won't accidentally collapse a list of three
        // duplicate paragraphs into one rendered block.
        val keys = blocks.map { it.key }.toSet()
        assertEquals(3, keys.size)
    }

    @Test
    fun `block content drift invalidates the key (defense against silent re-snapshot mutation)`() {
        // A snapshot-shaped re-emission that subtly mutates an
        // already-committed block (e.g. typo correction in an earlier
        // paragraph) MUST invalidate that block's key so the renderer
        // re-parses. Otherwise the user sees stale rendered text.
        val v1 = "First version.\n\nSecond.\n\n"
        val v2 = "First version!\n\nSecond.\n\n"
        val b1 = splitMarkdownBlocks(v1)
        val b2 = splitMarkdownBlocks(v2)
        assertEquals(2, b1.size)
        assertEquals(2, b2.size)
        // First block's key must differ — text changed.
        assertNotEquals(b1[0].key, b2[0].key)
        // Second block's text didn't change BUT its key MAY differ
        // (we don't promise identity across mutated-prior-block cases
        // because the index-based key is computed against the new
        // block's index, which equals the old one here, AND the
        // content hash, which is the same). So it should match.
        assertEquals(b1[1].key, b2[1].key)
    }
}
