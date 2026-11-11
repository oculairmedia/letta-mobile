package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceChunkerTest {
    private fun chunk(vararg deltas: String, max: Int = SentenceChunker.DEFAULT_MAX_SEGMENT_LENGTH): List<String> {
        val chunker = SentenceChunker(max)
        val pushed = deltas.flatMap { chunker.push(VoiceText(it)) }
        return (pushed + chunker.flush()).map { it.value }
    }

    @Test
    fun splitsAtSentenceEndsAcrossDeltas() {
        assertEquals(listOf("Hello there.", "How are you?", "Fine!"), chunk("Hel", "lo there. How are", " you? Fine!"))
    }

    @Test
    fun emitsASentenceOnlyOnceItsEndIsConfirmed() {
        val chunker = SentenceChunker()
        assertTrue(chunker.push(VoiceText("It is noon.")).isEmpty())
        assertEquals(listOf("It is noon."), chunker.push(VoiceText(" Next")).map { it.value })
        assertEquals(listOf("Next"), chunker.flush().map { it.value })
    }

    @Test
    fun keepsAbbreviationsInitialsAndDecimalsTogether() {
        assertEquals(
            listOf("Dr. Smith met J. Doe at 3.14 p.m. on Main St. today.", "Then left."),
            chunk("Dr. Smith met J. Doe at 3.14 p.m. on Main St. today. Then left."),
        )
    }

    @Test
    fun keepsClosingQuotesAndEllipses() {
        assertEquals(listOf("He said \"stop.\"", "Wait...", "Go."), chunk("He said \"stop.\" Wait... Go."))
    }

    @Test
    fun skipsFencedCodeEvenWhenSplitAcrossDeltas() {
        assertEquals(listOf("Here is code:", "Done."), chunk("Here is code:\n``", "`kotlin\nval x = 1. y\n``", "`\nDone."))
    }

    @Test
    fun dropsAnUnclosedCodeBlockOnFlush() {
        assertEquals(listOf("Look:"), chunk("Look: ```val a = 1"))
    }

    @Test
    fun stripsMarkdownLinksUrlsAndListMarkers() {
        assertEquals(
            listOf("Title", "Bold and code here.", "First item", "Second item", "See the docs.", "Visit now."),
            chunk(
                "# Title\n**Bold** and `code` here.\n- First item\n2. Second item\n",
                "See [the docs](https://x.io/a.b).\nVisit https://x.io now.",
            ),
        )
    }

    @Test
    fun keepsSnakeCaseUnderscores() {
        assertEquals(listOf("Call my_function now."), chunk("Call my_function now."))
    }

    @Test
    fun cutsOverlongTextAtAClauseBreak() {
        val long = "alpha beta gamma, delta epsilon zeta eta theta"
        val segments = chunk(long, max = 20)
        assertTrue(segments.all { it.length <= 20 })
        assertEquals("alpha beta gamma", segments.first())
    }

    @Test
    fun blankAndMarkupOnlyTextProducesNothing() {
        assertTrue(chunk("  ", "\n\n", "|---|---|\n").isEmpty())
    }

    @Test
    fun resetDropsBufferedText() {
        val chunker = SentenceChunker()
        chunker.push(VoiceText("Half a sent"))
        chunker.reset()
        assertTrue(chunker.flush().isEmpty())
    }
}
