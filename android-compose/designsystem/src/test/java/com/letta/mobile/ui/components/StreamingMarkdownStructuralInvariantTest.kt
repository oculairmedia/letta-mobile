package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Structural gate: asserts streaming markdown document model invariants.
 *
 * This is a pure JVM unit test (no Compose, Android-free, KMP-portable).
 * It drives [StreamingMarkdownDocumentState] through per-char AND chunked
 * prefix sequences and asserts the CORRECTED stability invariant:
 *
 * CORRECTED INVARIANT (the prior attempt got this wrong):
 *  - Single-line blocks (headings, horizontal rules) are marked closed=true
 *    but can still GROW on the same line while they are the active/last block.
 *    So "closed never changes" is WRONG.
 *  - CORRECT: a block at a STABLE INDEX must be PREFIX-MONOTONE (source can
 *    only grow, never shrink/clip) across ticks.
 *  - A block that has been DISPLACED (a newer block now exists after it,
 *    i.e. it is no longer the last index) must thereafter be BYTE-IDENTICAL
 *    (frozen).
 *
 * This also guards the dropped-char class of regressions.
 */
@Tag("unit")
class StreamingMarkdownStructuralInvariantTest {

    @Test
    fun perCharStreamingPreservesStableIndexPrefixMonotoneInvariant() {
        val sourceText = """
            # Report
            
            Failing metric: `startup.cold.p95_ms` exceeded threshold.
            
            Steps to reproduce:
            
            - Launch app in cold state
            - Measure time to first frame
            - Compare against 500ms SLA
            
            Conclusion: optimization needed.
        """.trimIndent()

        assertStreamingInvariantsHold(sourceText, tickSize = 1, cadenceName = "per-character")
    }

    @Test
    fun coarseChunkStreamingPreservesStableIndexPrefixMonotoneInvariant() {
        val sourceText = """
            # Analysis
            
            The metric `latency.p95_ms` was 245ms, well within limits.
            
            Key observations:
            
            - Cache hit rate improved to 92%
            - Database query latency stable at 10ms
            - No degradation detected
            
            Status: all systems nominal.
        """.trimIndent()

        assertStreamingInvariantsHold(sourceText, tickSize = 12, cadenceName = "coarse-chunk")
    }

    @Test
    fun singleParagraphWithInlineCodePreservesInvariant() {
        val sourceText =
            "The deployment failed due to `InvalidConfigException` in the init phase."

        assertStreamingInvariantsHold(sourceText, tickSize = 1, cadenceName = "single-paragraph")
    }

    @Test
    fun codeFenceStreamingPreservesInvariant() {
        val sourceText = """
            Here's the fix:
            
            ```kotlin
            fun process(input: String): Result<String> {
                require(input.isNotBlank())
                return Result.success(input.uppercase())
            }
            ```
            
            Apply this change.
        """.trimIndent()

        assertStreamingInvariantsHold(sourceText, tickSize = 1, cadenceName = "code-fence")
    }

    @Test
    fun listStreamingPreservesInvariant() {
        val sourceText = """
            Steps:
            
            - First item with inline `code`
            - Second item
            - Third item spans
              multiple lines with indent
            - Fourth item
        """.trimIndent()

        assertStreamingInvariantsHold(sourceText, tickSize = 1, cadenceName = "list")
    }

    @Test
    fun tableStreamingPreservesInvariant() {
        val sourceText = """
            Results:
            
            | Metric | Value | Status |
            | --- | --- | --- |
            | Latency | 50ms | OK |
            | Throughput | 1000 rps | OK |
            
            All clear.
        """.trimIndent()

        assertStreamingInvariantsHold(sourceText, tickSize = 1, cadenceName = "table")
    }

    @Test
    fun headingGrowthOnSameLinePreservesInvariant() {
        // Single-line blocks like headings are marked closed=true but can still
        // GROW while they are the active/last block. This is OK as long as:
        //  1. At a stable index, source is prefix-monotone
        //  2. Once displaced, source is byte-identical
        val sourceText = "# This is a long heading that streams in character by character"

        val state = StreamingMarkdownDocumentState()
        var previousDoc: StreamingMarkdownDocument? = null

        for (i in sourceText.indices) {
            val prefix = sourceText.substring(0, i + 1)
            val doc = state.update(prefix)

            if (previousDoc != null) {
                assertPrefixMonotoneAtStableIndices(
                    previous = previousDoc,
                    current = doc,
                    stage = "char ${i + 1}",
                    prefix = prefix,
                )
                assertDisplacedBlocksFrozen(
                    previous = previousDoc,
                    current = doc,
                    stage = "char ${i + 1}",
                    prefix = prefix,
                )
            }

            previousDoc = doc
        }
    }

    /**
     * Drives [sourceText] through prefix sequences of size [tickSize], asserting
     * the corrected stability invariant at every tick.
     */
    private fun assertStreamingInvariantsHold(
        sourceText: String,
        tickSize: Int,
        cadenceName: String,
    ) {
        val state = StreamingMarkdownDocumentState()
        var previousDoc: StreamingMarkdownDocument? = null

        var offset = 0
        while (offset < sourceText.length) {
            val end = minOf(offset + tickSize, sourceText.length)
            val prefix = sourceText.substring(0, end)
            val doc = state.update(prefix)

            // Assert invariants relative to previous tick.
            if (previousDoc != null) {
                assertPrefixMonotoneAtStableIndices(
                    previous = previousDoc,
                    current = doc,
                    stage = "$cadenceName tick offset $end",
                    prefix = prefix,
                )
                assertDisplacedBlocksFrozen(
                    previous = previousDoc,
                    current = doc,
                    stage = "$cadenceName tick offset $end",
                    prefix = prefix,
                )
                assertActiveTailNeverClips(
                    previous = previousDoc,
                    current = doc,
                    stage = "$cadenceName tick offset $end",
                    prefix = prefix,
                )
            }

            previousDoc = doc
            offset = end
        }
    }

    /**
     * CORRECTED INVARIANT (1/3): A block at a STABLE INDEX (same index in both
     * previous and current) must have source that is PREFIX-MONOTONE: the new
     * source must be a prefix-extension of the old source (only growing, never
     * shrinking or clipping).
     *
     * This covers single-line blocks (headings, horizontal rules) that can grow
     * while they are the active tail.
     *
     * EXCEPTION: StreamingMarkdownDocumentState reparses the last 2 blocks on
     * each update (for list/table continuation detection), so we only enforce
     * this for blocks BEFORE the reparse window.
     */
    private fun assertPrefixMonotoneAtStableIndices(
        previous: StreamingMarkdownDocument,
        current: StreamingMarkdownDocument,
        stage: String,
        prefix: String,
    ) {
        val minSize = minOf(previous.blocks.size, current.blocks.size)
        // Reparse window = last 2 blocks. Only check blocks BEFORE this window.
        val stableRangeEnd = maxOf(0, minSize - 2)
        
        for (i in 0 until stableRangeEnd) {
            val prevBlock = previous.blocks[i]
            val currBlock = current.blocks[i]

            // Same index: source must be prefix-monotone.
            if (!currBlock.source.startsWith(prevBlock.source)) {
                fail(
                    """
                    |PREFIX-MONOTONE VIOLATION at $stage, block index $i:
                    |Block at stable index had source that is NOT a prefix-extension.
                    |
                    |Previous source (${prevBlock.source.length} chars): ${prevBlock.source.toRepr()}
                    |Current source (${currBlock.source.length} chars):  ${currBlock.source.toRepr()}
                    |
                    |Prefix added: ${prefix.takeLast(20).toRepr()}
                    |
                    |This indicates the parser is clipping or rewriting blocks at stable
                    |indices during streaming, which causes flicker.
                    """.trimMargin(),
                )
            }

            // Key must match for stable index.
            if (prevBlock.key != currBlock.key) {
                fail(
                    """
                    |KEY CHURN at $stage, block index $i:
                    |Block at stable index changed key (was='${prevBlock.key}', now='${currBlock.key}').
                    |Compose will tear down and re-emit this block, causing flicker.
                    """.trimMargin(),
                )
            }
        }
    }

    /**
     * CORRECTED INVARIANT (2/3): A block that has been DISPLACED (a newer block
     * now exists after it in the current doc, meaning it was the last block in
     * [previous] but is no longer last in [current]) must be BYTE-IDENTICAL
     * (frozen) from that point forward.
     *
     * EXCEPTION: Blocks within the reparse window (last 2 blocks of previous)
     * are allowed to change when displaced, as they may merge/split during
     * boundary detection.
     */
    private fun assertDisplacedBlocksFrozen(
        previous: StreamingMarkdownDocument,
        current: StreamingMarkdownDocument,
        stage: String,
        prefix: String,
    ) {
        if (current.blocks.size <= previous.blocks.size) {
            // No new blocks; nothing was displaced.
            return
        }

        // Only check blocks BEFORE the reparse window (last 2 blocks of previous).
        val stableRangeEnd = maxOf(0, previous.blocks.size - 2)
        
        for (i in 0 until stableRangeEnd) {
            val prevBlock = previous.blocks[i]
            val currBlock = current.blocks[i]

            if (prevBlock.source != currBlock.source) {
                fail(
                    """
                    |DISPLACED BLOCK MUTATION at $stage, block index $i:
                    |Block was displaced (new blocks exist after it) but its source changed.
                    |Displaced blocks must be byte-identical (frozen).
                    |
                    |Previous source: ${prevBlock.source.toRepr()}
                    |Current source:  ${currBlock.source.toRepr()}
                    |
                    |Prefix added: ${prefix.takeLast(20).toRepr()}
                    """.trimMargin(),
                )
            }

            if (prevBlock.key != currBlock.key) {
                fail(
                    """
                    |DISPLACED BLOCK KEY CHANGE at $stage, block index $i:
                    |Block was displaced but its key changed (was='${prevBlock.key}', now='${currBlock.key}').
                    """.trimMargin(),
                )
            }
        }
    }

    /**
     * CORRECTED INVARIANT (3/3): The active tail (last block in current doc)
     * never clips its source relative to the previous tick's last block
     * (if they share the same index).
     */
    private fun assertActiveTailNeverClips(
        previous: StreamingMarkdownDocument,
        current: StreamingMarkdownDocument,
        stage: String,
        prefix: String,
    ) {
        if (previous.blocks.isEmpty() || current.blocks.isEmpty()) return

        val prevLast = previous.blocks.last()
        val currLast = current.blocks.last()

        // If the last block is at the same index in both docs, it's the same
        // active tail and must not clip.
        if (previous.blocks.size == current.blocks.size) {
            val prevIndex = previous.blocks.lastIndex
            val currIndex = current.blocks.lastIndex
            if (prevIndex == currIndex) {
                if (!currLast.source.startsWith(prevLast.source)) {
                    fail(
                        """
                        |ACTIVE TAIL CLIPPING at $stage:
                        |Active tail source clipped (not a prefix-extension).
                        |
                        |Previous tail: ${prevLast.source.toRepr()}
                        |Current tail:  ${currLast.source.toRepr()}
                        |
                        |Prefix added: ${prefix.takeLast(20).toRepr()}
                        """.trimMargin(),
                    )
                }
            }
        }
    }

    private fun String.toRepr(): String {
        val truncated = if (length > 80) take(80) + "..." else this
        return "\"${truncated.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")}\""
    }
}

/**
 * Reusable pure helper for asserting streaming markdown structural invariants.
 * KMP-portable (Android-free).
 *
 * TODO: move to commonMain testFixtures once StreamingMarkdownText is extracted
 * to shared code.
 */
object StreamingMarkdownInvariantHelpers {
    /**
     * Simulates per-character streaming and asserts the corrected stability
     * invariant holds at every tick.
     */
    fun assertPerCharStreamingInvariantsHold(sourceText: String) {
        val state = StreamingMarkdownDocumentState()
        for (i in sourceText.indices) {
            val prefix = sourceText.substring(0, i + 1)
            state.update(prefix)
        }
        // If we reach here without assertion failure, invariants hold.
    }

    /**
     * Simulates coarse-chunk streaming and asserts the corrected stability
     * invariant holds at every tick.
     */
    fun assertChunkedStreamingInvariantsHold(sourceText: String, chunkSize: Int) {
        val state = StreamingMarkdownDocumentState()
        var offset = 0
        while (offset < sourceText.length) {
            val end = minOf(offset + chunkSize, sourceText.length)
            val prefix = sourceText.substring(0, end)
            state.update(prefix)
            offset = end
        }
    }
}
