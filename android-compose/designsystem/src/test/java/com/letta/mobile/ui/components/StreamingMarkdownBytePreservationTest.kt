package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * letta-mobile-h30cy: the Iroh duplicate persisted because the STREAMED assistant
 * row rendered with punctuation/words stripped while the reconciled final rendered
 * clean — so the two contents never matched and the optimistic-twin dedup could
 * not collapse them. The wire + reducer are clean (verified via probe + reducer
 * replay); the mangle is in the streaming markdown render. This harness streams
 * text PER-CHARACTER through partitionStreamingMarkdown and asserts that at EVERY
 * prefix the parsed blocks + active tail reconstruct the EXACT input prefix
 * (byte-preservation). Any dropped punctuation/whitespace fails here.
 */
class StreamingMarkdownBytePreservationTest {

    private fun reconstruct(prefix: String): String {
        val p = partitionStreamingMarkdown(prefix)
        return p.committedBlocks.joinToString("") { it.text } + p.activeTail
    }

    @Test
    fun `punctuation-heavy prose is byte-preserved at every streaming prefix`() {
        val source = "Sure — here's a sentence with commas, periods. And an em dash—like this: " +
            "I went to the store, bought some apples, and headed home. Don't lose the punctuation!"
        for (end in 1..source.length) {
            val prefix = source.substring(0, end)
            assertEquals("prefix len $end must round-trip", prefix, reconstruct(prefix))
        }
    }

    @Test
    fun `multiline prose with punctuation is byte-preserved`() {
        val source = "First line, with a comma.\nSecond line — with a dash.\nThird: a colon, and done."
        for (end in 1..source.length) {
            val prefix = source.substring(0, end)
            assertEquals("prefix len $end must round-trip", prefix, reconstruct(prefix))
        }
    }

    @Test
    fun `repair pass does not drop punctuation from plain streamed prose`() {
        // repairIncompleteMarkdownForStreaming runs ONLY on the streamed tail (not
        // the reconciled final) — the exact stream-vs-final asymmetry. Plain prose
        // with punctuation must pass through untouched at every streaming prefix.
        val source = "we've been here before — clean for a stretch, then it resurfaces. " +
            "Don't trust it yet. Keep the harness running, keep the data flowing. I'll be here either way."
        for (end in 1..source.length) {
            val prefix = source.substring(0, end)
            val repaired = repairIncompleteMarkdownForStreaming(prefix)
            // The repair may only APPEND closing markers to the tail; it must never
            // DROP characters from the source prefix.
            assertEquals(
                "repair dropped chars at prefix len $end",
                prefix,
                repaired.substring(0, minOf(prefix.length, repaired.length)),
            )
            // and never shrink below the input
            if (repaired.length < prefix.length) {
                throw AssertionError("repair SHRANK prefix len $end: '$prefix' -> '$repaired'")
            }
        }
    }

}
