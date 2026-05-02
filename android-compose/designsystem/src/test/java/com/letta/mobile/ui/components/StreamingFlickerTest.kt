package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-flk2 (debug suite): programmatic flicker simulator.
 *
 * The on-device flicker Emmanuel reports is fundamentally about
 * consecutive renders producing different bytes for the same logical
 * position. If the streaming pipeline is correct, then for a strictly
 * append-only input stream:
 *
 *  - The list of committed blocks must be append-only across ticks
 *    (no rewrites of already-committed bytes).
 *  - Each committed block's `text` and `key` must be byte-identical
 *    across every tick where it remains committed.
 *  - The active-tail length must be bounded (it can't grow without
 *    bound; eventually it must roll over into a committed block).
 *
 * Any violation of those properties is, by definition, the flicker
 * source. This test ticks a fixture corpus through the streaming
 * partitioner one character at a time (simulating the worst case:
 * a paint sample on every char) and asserts the properties hold for
 * every prefix.
 *
 * If a test here fails, the flicker is reproducible without a device
 * and the failing prefix is the minimal repro.
 */
@Tag("unit")
class StreamingFlickerTest {

    /**
     * Drives [text] through `partitionStreamingMarkdown` one char at
     * a time, returning the sequence of partitions for each prefix
     * length 1..N. A real device sees a (possibly sparser) subset of
     * these prefixes; testing every prefix covers all possible paint
     * cadences including the worst case of one tick per char.
     */
    private fun simulate(text: String): List<StreamingMarkdownPartition> {
        return (1..text.length).map { partitionStreamingMarkdown(text.substring(0, it)) }
    }

    /**
     * Asserts the committed-blocks list is append-only across the
     * whole tick sequence: at every step, the first `previous.size`
     * blocks of the current step must byte-equal the previous step.
     *
     * If this fails, two consecutive prefixes produced different
     * bytes for the same block index — the renderer would tear down
     * and re-emit that block subtree. That's flicker.
     */
    private fun assertCommittedBlocksAppendOnly(
        text: String,
        partitions: List<StreamingMarkdownPartition>,
    ) {
        for (i in 1 until partitions.size) {
            val prev = partitions[i - 1].committedBlocks
            val curr = partitions[i].committedBlocks
            if (curr.size < prev.size) {
                fail(
                    "REGRESSION at prefix len ${i + 1}: committed block " +
                        "count went from ${prev.size} to ${curr.size} " +
                        "(committed blocks must never disappear).\n" +
                        "Prefix added char: ${text.getOrNull(i)?.toRepr()}\n" +
                        "Previous prefix end: ${text.substring(0, i).takeLast(40).toRepr()}",
                )
                return
            }
            for (j in prev.indices) {
                val pBlock = prev[j]
                val cBlock = curr[j]
                if (pBlock.text != cBlock.text) {
                    fail(
                        "FLICKER at prefix len ${i + 1}, block index $j: " +
                            "committed block text changed.\n" +
                            "  was:  ${pBlock.text.toRepr()}\n" +
                            "  now:  ${cBlock.text.toRepr()}\n" +
                            "Char added: ${text.getOrNull(i)?.toRepr()}",
                    )
                    return
                }
                if (pBlock.key != cBlock.key) {
                    fail(
                        "KEY CHURN at prefix len ${i + 1}, block index $j: " +
                            "committed block key changed " +
                            "(was='${pBlock.key}', now='${cBlock.key}'). " +
                            "Compose will tear down and re-emit this block.",
                    )
                    return
                }
            }
        }
    }

    /**
     * Asserts no committed-block text contains an unclosed code fence
     * or display-math fence. If a committed block is missing its
     * closer, mikepenz will absorb subsequent content into it on the
     * next paint tick → the block will visibly mutate → flicker.
     */
    private fun assertCommittedBlocksAreFenceClosed(
        partitions: List<StreamingMarkdownPartition>,
    ) {
        for ((i, p) in partitions.withIndex()) {
            for ((j, blk) in p.committedBlocks.withIndex()) {
                val tripleFences = countTriple(blk.text, '`')
                val dollarFences = countDouble(blk.text, '$')
                if (tripleFences % 2 != 0) {
                    fail(
                        "UNCLOSED CODE FENCE in committed block " +
                            "(prefix len ${i + 1}, block $j): " +
                            "${blk.text.toRepr()}",
                    )
                    return
                }
                if (dollarFences % 2 != 0) {
                    fail(
                        "UNCLOSED MATH FENCE in committed block " +
                            "(prefix len ${i + 1}, block $j): " +
                            "${blk.text.toRepr()}",
                    )
                    return
                }
            }
        }
    }

    /**
     * Asserts the active tail does not grow unboundedly. If the entire
     * document accumulates in the tail, the partitioner is failing to
     * commit blocks at boundaries and the renderer has nothing stable
     * to skip-recompose against. We allow the tail to be the whole
     * input only when there's literally no `\n\n` paragraph break
     * anywhere in the text yet (one-paragraph-so-far case).
     */
    private fun assertActiveTailBounded(
        text: String,
        partitions: List<StreamingMarkdownPartition>,
        maxTailRatio: Double = 0.9,
    ) {
        for ((i, p) in partitions.withIndex()) {
            val prefix = text.substring(0, i + 1)
            // If the prefix has no paragraph break and no fully-closed
            // block fence, it's legitimate for everything to be in the
            // tail. We require an ACTIVE closed fence (not just zero
            // fences) to count as a boundary candidate.
            val hasParaBreak = "\n\n" in prefix
            val hasClosedTripleFence = countTriple(prefix, '`').let { it >= 2 && it % 2 == 0 }
            val hasClosedMathFence = countDouble(prefix, '$').let { it >= 2 && it % 2 == 0 }
            val hasBoundaryCandidate = hasParaBreak || hasClosedTripleFence || hasClosedMathFence
            if (!hasBoundaryCandidate) continue

            val ratio = p.activeTail.length.toDouble() / prefix.length
            if (ratio > maxTailRatio) {
                fail(
                    "TAIL UNBOUNDED at prefix len ${i + 1}: " +
                        "tail/prefix ratio = ${"%.2f".format(ratio)} " +
                        "(>$maxTailRatio); committed blocks = ${p.committedBlocks.size}\n" +
                        "tail head: ${p.activeTail.take(80).toRepr()}",
                )
                return
            }
        }
    }

    // ─── Fixtures: representative streaming responses ────────────────────

    @Test
    fun `simple two-paragraph response is flicker-free`() {
        val text = """
            # Heading

            This is the first paragraph with some text.

            This is the second paragraph that wraps onto multiple lines and contains a bit more content to make sure the partitioner has enough text to commit blocks.
        """.trimIndent()

        val partitions = simulate(text)
        assertCommittedBlocksAppendOnly(text, partitions)
        assertCommittedBlocksAreFenceClosed(partitions)
        assertActiveTailBounded(text, partitions)
    }

    @Test
    fun `code block streaming is flicker-free`() {
        val text = """
            Here's a function.

            ```kotlin
            fun greet(name: String): String {
                return "Hello, ${'$'}name!"
            }
            ```

            That's the implementation.
        """.trimIndent()

        val partitions = simulate(text)
        assertCommittedBlocksAppendOnly(text, partitions)
        assertCommittedBlocksAreFenceClosed(partitions)
    }

    @Test
    fun `inline emphasis mid-paragraph never commits unclosed markers`() {
        val text = "Here is **bold** and *italic* and `code` mixed together in one paragraph that goes on for a while and then continues with **another bold span** before ending.\n\nNext paragraph here."
        val partitions = simulate(text)
        assertCommittedBlocksAppendOnly(text, partitions)

        // Stronger property for inline emphasis: the active tail at
        // every step must NOT contain any inline marker that opens
        // without a closer in the SAME tail. Otherwise the renderer
        // shows raw `**bold` then snaps to bold on closer arrival.
        for ((i, p) in partitions.withIndex()) {
            val tail = p.activeTail
            // Per-line check for unmatched inline markers.
            tail.split('\n').forEachIndexed { lineIdx, line ->
                if (line.isEmpty()) return@forEachIndexed
                val unmatched = unmatchedInlineMarker(line)
                if (unmatched != null) {
                    // Note: we DO expect unmatched markers in the tail
                    // sometimes — that's literally what the tail is for.
                    // What we really want is that those unmatched markers
                    // never appear in COMMITTED blocks (asserted
                    // separately below).
                    return@forEachIndexed
                }
            }
            for ((j, blk) in p.committedBlocks.withIndex()) {
                blk.text.split('\n').forEachIndexed { _, line ->
                    if (line.isEmpty()) return@forEachIndexed
                    val unmatched = unmatchedInlineMarker(line)
                    if (unmatched != null) {
                        fail(
                            "Committed block contains unmatched inline " +
                                "marker '$unmatched' at prefix len ${i + 1}, " +
                                "block $j: ${blk.text.toRepr()}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `list streaming keeps committed list items stable`() {
        val text = """
            Steps:

            - First item
            - Second item with more text
            - Third item
            - Fourth and final item

            Done.
        """.trimIndent()

        val partitions = simulate(text)
        assertCommittedBlocksAppendOnly(text, partitions)
    }

    @Test
    fun `mixed response with heading paragraph code list is flicker-free`() {
        // Mirrors a typical assistant streaming response.
        val text = """
            # Implementation Plan

            Here's how I'd approach this problem step by step.

            ```kotlin
            fun process(input: String): Result<String> {
                if (input.isBlank()) return Result.failure(IllegalArgumentException())
                return Result.success(input.uppercase())
            }
            ```

            The function above:

            - Validates the input
            - Returns a Result wrapper
            - Handles the blank case explicitly

            That should give you what you need.
        """.trimIndent()

        val partitions = simulate(text)
        assertCommittedBlocksAppendOnly(text, partitions)
        assertCommittedBlocksAreFenceClosed(partitions)
        assertActiveTailBounded(text, partitions)
    }

    /**
     * letta-mobile-flk2 diagnostic: verifies the **active tail** is
     * append-only at the byte level across consecutive ticks.
     *
     * If the tail is provably append-only, then the per-tick flicker
     * the user sees on the in-progress paragraph cannot be a
     * "content shift" — it must be the **renderer** re-emitting
     * the same mikepenz subtree at the paint cadence. That points
     * the fix squarely at: render the active tail with plain
     * Text/AnnotatedString, not MarkdownText.
     *
     * If this test FAILS, the flicker has a different origin
     * (the tail content actually changes byte-for-byte mid-stream),
     * and the plain-Text fix won't help.
     */
    @Test
    fun `active tail is byte-append-only across consecutive ticks`() {
        val fixtures = listOf(
            "# Heading\n\nFirst paragraph with some text here that streams in.",
            "Here is **bold text** and *italic* with a partial **bold at en",
            "Steps:\n\n- First item\n- Second item with text\n- Third item",
            "Code follows:\n\n```kotlin\nfun foo() {\n    println(\"hi\")\n}\n```\n\nDone.",
        )

        for (fixture in fixtures) {
            val partitions = simulate(fixture)
            // Logical position of the tail's start in the source text,
            // tick by tick. Should be monotonically non-decreasing
            // (boundaries advance forward only).
            var prevTailStart = 0
            var prevTail = ""
            for ((i, p) in partitions.withIndex()) {
                val committedLen = p.committedBlocks.sumOf { it.text.length }
                val tailStart = committedLen
                val tail = p.activeTail

                // Tail-start must advance forward only.
                if (tailStart < prevTailStart) {
                    fail(
                        "Tail start regressed at prefix len ${i + 1} " +
                            "($prevTailStart → $tailStart) for fixture: " +
                            fixture.take(40).toRepr(),
                    )
                }

                // When tail-start hasn't moved, new tail must extend old tail.
                if (tailStart == prevTailStart && prevTail.isNotEmpty()) {
                    if (!tail.startsWith(prevTail) && !prevTail.startsWith(tail)) {
                        // Allow shrinking iff a boundary was found
                        // mid-tick (handled by tailStart change above);
                        // here tailStart didn't change so the new tail
                        // must be a prefix-extension of the old.
                        fail(
                            "Tail content REWRITTEN at prefix len ${i + 1} " +
                                "(tailStart unchanged at $tailStart):\n" +
                                "  prev: ${prevTail.takeLast(40).toRepr()}\n" +
                                "  curr: ${tail.takeLast(40).toRepr()}",
                        )
                    }
                }
                prevTailStart = tailStart
                prevTail = tail
            }
        }
    }

    @Test
    fun `single very long paragraph keeps tail bounded only at end`() {
        // No paragraph breaks. The whole thing is one tail until the
        // final char. That's expected; just don't crash.
        val text = "This is a single very long paragraph with no breaks ".repeat(20)
        val partitions = simulate(text)
        assertCommittedBlocksAppendOnly(text, partitions)
        // Last partition: still all tail (no \n\n in text).
        assertEquals(0, partitions.last().committedBlocks.size)
        assertEquals(text, partitions.last().activeTail)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun countTriple(s: String, ch: Char): Int {
        var count = 0
        var i = 0
        while (i + 2 < s.length) {
            if (s[i] == ch && s[i + 1] == ch && s[i + 2] == ch) {
                count++
                i += 3
            } else {
                i++
            }
        }
        return count
    }

    private fun countDouble(s: String, ch: Char): Int {
        var count = 0
        var i = 0
        while (i + 1 < s.length) {
            if (s[i] == ch && s[i + 1] == ch) {
                count++
                i += 2
            } else {
                i++
            }
        }
        return count
    }

    /**
     * Returns the unmatched inline marker on a single line, or null
     * if all markers are balanced. Detects: `*`, `**`, `***`, `_`,
     * `__`, `` ` ``, `~~`, `[`.
     */
    private fun unmatchedInlineMarker(line: String): String? {
        var i = 0
        val len = line.length
        while (i < len) {
            val c = line[i]
            when (c) {
                '`' -> {
                    val close = line.indexOf('`', startIndex = i + 1)
                    if (close < 0) return "`"
                    i = close + 1
                }
                '*', '_' -> {
                    var run = 1
                    while (i + run < len && line[i + run] == c) run++
                    val later = findRun(line, i + run, c, run)
                    if (later < 0) return c.toString().repeat(run)
                    i = later + run
                }
                '~' -> {
                    if (i + 1 < len && line[i + 1] == '~') {
                        val close = line.indexOf("~~", startIndex = i + 2)
                        if (close < 0) return "~~"
                        i = close + 2
                    } else {
                        i++
                    }
                }
                '[' -> {
                    val cb = line.indexOf(']', startIndex = i + 1)
                    if (cb < 0) return "["
                    if (cb + 1 >= len || line[cb + 1] != '(') {
                        // Reference-style or footnote — accept as
                        // balanced once `]` is seen.
                        i = cb + 1
                    } else {
                        val cp = line.indexOf(')', startIndex = cb + 2)
                        if (cp < 0) return "[("
                        i = cp + 1
                    }
                }
                else -> i++
            }
        }
        return null
    }

    private fun findRun(s: String, from: Int, ch: Char, runLen: Int): Int {
        var i = from
        val n = s.length
        while (i < n) {
            if (s[i] == ch) {
                var run = 1
                while (i + run < n && s[i + run] == ch) run++
                if (run >= runLen) return i
                i += run
            } else i++
        }
        return -1
    }

    private fun String.toRepr(): String =
        "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""

    private fun Char.toRepr(): String = "'$this' (U+%04X)".format(code)
}
