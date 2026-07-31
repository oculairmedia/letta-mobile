package com.letta.mobile.ui.components

import com.letta.mobile.ui.markdown.StreamingMarkdownDocumentState

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral recomposition gate: verifies a markdown block stops recomposing
 * once it has been committed (i.e. once a newer block has displaced it as the
 * streaming tail).
 *
 * This gate protects against:
 *  - Composition loops (blocks recomposing after their source stopped changing)
 *  - Block key churn causing unnecessary teardown/recreation
 *  - Non-monotone block updates causing layout thrashing
 *
 * ## How the measurement works
 *
 * Each block is rendered through [InstrumentedMarkdownBlock], a *restartable*
 * composable whose parameters are exactly `(blockKey, source, recorder)`. All
 * three are stable and compare equal across ticks for a committed block, so
 * Compose skips the whole call and its `SideEffect` never runs. That makes the
 * counter measure real recomposition of the block itself rather than
 * recomposition of the enclosing scope.
 *
 * The counter is then split at the moment a block is *committed* (the first
 * frame in which it is no longer the last block). Compositions before that point
 * are the block's legitimate active-tail churn - its source genuinely changes on
 * every tick. Compositions **after** that point are the regression this gate
 * exists to catch, and are asserted against a small constant that does not scale
 * with the stream length. A per-tick recomposition regression therefore fails by
 * an O(N) margin instead of sitting one composition below the limit.
 *
 * NOTE: This test measures composition FREQUENCY, not composition COST. The
 * `retainState` regression in `MarkdownText`/CoreMarkdown (full reparse per
 * recomposition) is not visible here; it is covered by the structural invariant
 * tests and by manual device testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class StreamingMarkdownRecompositionGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Per-character streaming cadence (Android typical). Drives a multi-block
     * markdown document char-by-char, instruments each block render, and asserts
     * committed blocks recompose O(1) times regardless of stream length.
     */
    @Test
    fun committedBlocksDoNotRecomposeOnEveryCharacterTick() {
        // Source text with inline code span + multiple paragraphs/list items.
        // This ensures we have several committed blocks + an active tail.
        val sourceText = """
            # Analysis Results

            The system identified a critical issue: `startup.cold.p95_ms` exceeded the 500ms SLA threshold.

            Root causes identified:

            - Database connection pool exhaustion
            - Cold-start lambda initialization overhead
            - Inefficient query patterns in the auth layer

            Recommendation: implement connection pooling warmup strategy.
        """.trimIndent()

        val recorder = BlockCompositionRecorder()
        val text = mutableStateOf("")

        composeRule.setContent {
            LettaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        StreamingMarkdownTextWithInstrumentation(
                            text = text.value,
                            recorder = recorder,
                        )
                    }
                }
            }
        }

        // Drive text char-by-char (sample every 5 chars to keep test fast).
        composeRule.waitForIdle()
        var tickCount = 0
        for (i in sourceText.indices step 5) {
            composeRule.runOnIdle {
                text.value = sourceText.substring(0, i + 1)
            }
            composeRule.waitForIdle()
            tickCount++
        }

        // Final frame: text is complete.
        composeRule.runOnIdle {
            text.value = sourceText
        }
        composeRule.waitForIdle()
        tickCount++

        assertCommittedBlocksStayBounded(
            recorder = recorder,
            tickCount = tickCount,
            cadence = "per-character (sampled every 5 chars)",
            sourceLength = sourceText.length,
        )
    }

    /**
     * Coarse multi-char chunk cadence (desktop typical). Drives markdown with
     * larger chunks appended at ~50ms intervals to simulate desktop streaming.
     */
    @Test
    fun committedBlocksDoNotRecomposeOnEveryChunkTick() {
        val sourceText = """
            # Performance Report

            Measured latency across 1000 requests. The P95 metric `latency.p95_ms` was 245ms.

            Key findings:

            - Cache hit rate: 87%
            - Database query time: 12ms avg
            - Network overhead: 8ms avg
            - Serialization: 3ms avg

            Overall system health is good. No action required.
        """.trimIndent()

        val recorder = BlockCompositionRecorder()
        val text = mutableStateOf("")

        composeRule.setContent {
            LettaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        StreamingMarkdownTextWithInstrumentation(
                            text = text.value,
                            recorder = recorder,
                        )
                    }
                }
            }
        }

        // Drive text in coarse chunks (~10-20 chars at a time).
        composeRule.waitForIdle()
        val chunkSize = 15
        var offset = 0
        var tickCount = 0
        while (offset < sourceText.length) {
            val end = minOf(offset + chunkSize, sourceText.length)
            composeRule.runOnIdle {
                text.value = sourceText.substring(0, end)
            }
            composeRule.waitForIdle()
            offset = end
            tickCount++
        }

        assertCommittedBlocksStayBounded(
            recorder = recorder,
            tickCount = tickCount,
            cadence = "coarse-chunk",
            sourceLength = sourceText.length,
        )
    }

    /**
     * Records per-block composition counts and, for each block, the count at the
     * instant it was committed (displaced from the tail).
     *
     * Marked [Stable] and always passed as the same instance, so it never itself
     * defeats skipping of the block it instruments.
     */
    @Stable
    private class BlockCompositionRecorder {
        /** Total compositions observed per block key, in first-seen order. */
        val totalCompositions = linkedMapOf<String, Int>()

        /**
         * Composition count of each block as of the last frame in which it was
         * still the streaming tail.
         *
         * Re-taken on every such frame rather than only the first: the parser can
         * legitimately hand the tail role back to an earlier block (a list block
         * regains the tail when the speculative block after it merges back in),
         * and that block's churn while it is the tail again is by design.
         */
        private val tailBaseline = linkedMapOf<String, Int>()

        /** Blocks that are still not committed, i.e. the tail of the last frame. */
        private var currentTailKey: String? = null

        fun recordComposition(blockKey: String) {
            totalCompositions[blockKey] = (totalCompositions[blockKey] ?: 0) + 1
        }

        /** Called once per frame, after the block SideEffects for that frame have run. */
        fun recordFrame(blockKeys: List<String>) {
            val tail = blockKeys.lastOrNull() ?: return
            val outgoingTail = currentTailKey
            if (outgoingTail != null && outgoingTail != tail) {
                // Commit frame: the outgoing tail may compose one final time here to
                // apply its closing source (e.g. the newline that seals it). That is
                // part of committing, not per-tick churn, so it is folded into the
                // baseline. Everything after this frame is a genuine violation.
                tailBaseline[outgoingTail] = totalCompositions[outgoingTail] ?: 0
            }
            currentTailKey = tail
            tailBaseline[tail] = totalCompositions[tail] ?: 0
        }

        /**
         * Compositions each committed block accrued *after* it last held the tail.
         * A renderer that skip-recomposes committed blocks keeps these at ~0.
         *
         * The final frame's tail is excluded: it never became committed, so its
         * churn is expected.
         */
        fun postCommitCompositions(): Map<String, Int> =
            totalCompositions.keys
                .filter { it != currentTailKey }
                .associateWith { key ->
                    (totalCompositions[key] ?: 0) - (tailBaseline[key] ?: 0)
                }
    }

    /**
     * Renders StreamingMarkdownText blocks and instruments each block with a
     * SideEffect composition counter.
     *
     * This mirrors the real StreamingMarkdownDocumentBlocks structure: each block
     * is keyed, and MarkdownText is called with the block's source. The `key()`
     * wrapper preserves component identity across ticks; the restartable
     * [InstrumentedMarkdownBlock] lets Compose skip unchanged blocks entirely.
     */
    @Composable
    private fun StreamingMarkdownTextWithInstrumentation(
        text: String,
        recorder: BlockCompositionRecorder,
    ) {
        val documentState = remember { StreamingMarkdownDocumentState() }
        // Update the document state on each text change, preserving block identity.
        val document = documentState.update(text)

        // Use Column (like real StreamingMarkdownText) to ensure proper layout.
        Column {
            document.blocks.forEach { block ->
                key(block.key) {
                    InstrumentedMarkdownBlock(
                        blockKey = block.key,
                        source = block.source,
                        recorder = recorder,
                    )
                }
            }
        }

        // Emitted after the per-block SideEffects above, so the baselines it
        // captures already include this frame's compositions.
        val blockKeys = document.blocks.map { it.key }
        SideEffect { recorder.recordFrame(blockKeys) }
    }

    /**
     * Restartable composable wrapping one markdown block. Because every parameter
     * is stable and compares equal across ticks for an unchanged block, Compose
     * skips this call outright and the [SideEffect] does not run - which is
     * exactly the property under test.
     */
    @Composable
    private fun InstrumentedMarkdownBlock(
        blockKey: String,
        source: String,
        recorder: BlockCompositionRecorder,
    ) {
        SideEffect { recorder.recordComposition(blockKey) }
        MarkdownText(
            text = source,
            textColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    /**
     * Asserts that once a block is committed (displaced from the streaming tail)
     * it stops recomposing.
     *
     * The bound is an absolute constant, deliberately independent of [tickCount]:
     * a per-tick recomposition regression overshoots it by O(N), so the gate can
     * never be tipped either way by a frame or two of scheduling noise on a
     * loaded CI runner.
     */
    private fun assertCommittedBlocksStayBounded(
        recorder: BlockCompositionRecorder,
        tickCount: Int,
        cadence: String,
        sourceLength: Int,
    ) {
        val totals = recorder.totalCompositions.toMap()
        if (totals.isEmpty()) {
            fail("No blocks were rendered; test setup may be broken")
        }

        val postCommit = recorder.postCommitCompositions()
        if (postCommit.isEmpty()) {
            fail(
                "Only one block was ever rendered across $tickCount ticks; the gate " +
                    "needs at least one committed block to validate. Totals: $totals",
            )
        }

        val violations = postCommit
            .filterValues { it > MAX_POST_COMMIT_COMPOSITIONS }
            .map { (key, count) ->
                "  Block $key: $count compositions after commit " +
                    "(limit=$MAX_POST_COMMIT_COMPOSITIONS, total=${totals[key]})"
            }

        if (violations.isNotEmpty()) {
            fail(
                """
                |FLICKER DETECTED ($cadence streaming, source length $sourceLength chars,
                |$tickCount streaming ticks):
                |Committed blocks kept recomposing after they were displaced from the tail.
                |Expected O(1) post-commit compositions, got O(N) per tick.
                |
                |This indicates the markdown renderer is re-emitting committed blocks on
                |every streaming tick instead of skip-recomposing them.
                |
                |Likely causes:
                | - Block key churn in StreamingMarkdownDocumentState
                | - Non-monotone block updates (committed block source mutating)
                | - An unstable parameter reaching the block composable, defeating skipping
                |
                |Post-commit composition counts:
                |${violations.joinToString("\n")}
                |
                |All total composition counts: $totals
                |All post-commit composition counts: $postCommit
                """.trimMargin(),
            )
        }

        // Sanity: the stream must actually have churned, otherwise a broken
        // harness that renders nothing would pass vacuously.
        assertTrue(
            "Expected the stream to produce multiple blocks over $tickCount ticks, got $totals",
            totals.size >= MIN_EXPECTED_BLOCKS,
        )
        assertTrue(
            "Expected the active tail to recompose as the stream advances, got $totals",
            totals.values.any { it > 1 },
        )
    }

    private companion object {
        /**
         * Allow a single post-commit composition: the frame that commits a block
         * may also be the frame that applies its final source (for example the
         * trailing newline that closes it).
         */
        const val MAX_POST_COMMIT_COMPOSITIONS = 1

        /** Both fixtures produce a heading, prose, a list, and a closing paragraph. */
        const val MIN_EXPECTED_BLOCKS = 4
    }
}
