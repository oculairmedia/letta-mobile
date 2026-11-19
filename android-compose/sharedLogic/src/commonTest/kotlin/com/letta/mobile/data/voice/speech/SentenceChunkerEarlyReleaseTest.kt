package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The first segment released at its first long-enough clause, later ones sentence-sized. */
class SentenceChunkerEarlyReleaseTest {
    private fun early(vararg deltas: String, minimum: Int = MIN): List<String> {
        val chunker = SentenceChunker(firstClauseMinLength = minimum)
        val pushed = deltas.flatMap { chunker.push(VoiceText(it)) }
        return (pushed + chunker.flush()).map { it.value }
    }

    @Test
    fun theFirstClauseIsReleasedOnceLongEnoughThenSentencesFollow() {
        val text = "Here is the short answer to your question, and then the rest of it. Then, a second sentence, with commas."
        assertEquals(
            listOf(
                "Here is the short answer to your question",
                "and then the rest of it.",
                "Then, a second sentence, with commas.",
            ),
            early(text),
        )
    }

    @Test
    fun theClauseIsReleasedAsSoonAsTheTextAfterItArrives() {
        val chunker = SentenceChunker(firstClauseMinLength = MIN)
        assertTrue(chunker.push(VoiceText("Here is the short answer to your question,")).isEmpty(), "the break needs its whitespace")
        assertEquals(listOf("Here is the short answer to your question"), chunker.push(VoiceText(" and")).map { it.value })
    }

    @Test
    fun aClauseShorterThanTheMinimumWaitsForALaterBreakOrTheSentenceEnd() {
        assertEquals(listOf("Well, yes.", "And more."), early("Well, yes. And more."))
        assertEquals(
            listOf("Well, I think the answer is fairly simple here;", "it is the cache."),
            early("Well, I think the answer is fairly simple here; it is the cache."),
        )
    }

    @Test
    fun aSentenceEndBeforeAnyLongClauseWins() {
        assertEquals(
            listOf("Yes.", "That is right, and here is a long clause after it."),
            early("Yes. That is right, and here is a long clause after it."),
        )
    }

    @Test
    fun dashesColonsAndSemicolonsAreClauseBreaks() {
        assertEquals(
            listOf("The deployment finished without any problems -", "all green."),
            early("The deployment finished without any problems - all green."),
        )
        assertEquals(
            listOf("The deployment finished without any problems—", "all green."),
            early("The deployment finished without any problems— all green."),
        )
        assertEquals(
            listOf("There are three things you should know about this:", "one, two, three."),
            early("There are three things you should know about this: one, two, three."),
        )
    }

    @Test
    fun numbersAbbreviationsAndHyphenatedWordsAreNeverClauseBreaks() {
        assertEquals(
            listOf("The total came to 1,000,000 dollars at 10:30 this morning for the well-known build.", "Done."),
            early("The total came to 1,000,000 dollars at 10:30 this morning for the well-known build. Done."),
        )
        assertEquals(
            listOf("Several common formats are supported, e.g., WAV and FLAC and more", "for sure."),
            early("Several common formats are supported, e.g., WAV and FLAC and more, for sure."),
            "the comma after the abbreviation is skipped; the next long-enough break is used",
        )
        assertEquals(listOf("Dr. Smith said so.", "Fine."), early("Dr. Smith said so. Fine."))
    }

    @Test
    fun onlyTheFirstSegmentIsReleasedEarlyEvenAcrossFlushes() {
        val chunker = SentenceChunker(firstClauseMinLength = MIN)
        val first = chunker.push(VoiceText("Here is the short answer to your question, more")) + chunker.flush()
        val second = chunker.push(VoiceText("Another long part follows here after the flush, with a comma.")) + chunker.flush()
        assertEquals(listOf("Here is the short answer to your question", "more"), first.map { it.value })
        assertEquals(listOf("Another long part follows here after the flush, with a comma."), second.map { it.value })
    }

    @Test
    fun aClauseInsideALinkIsNotABreak() {
        assertEquals(
            listOf("Please read the complete and very long guide, which covers it for details.", "Ok."),
            early("Please read the [complete and very long guide, which covers it](https://x.example/a) for details. Ok."),
        )
    }

    @Test
    fun withoutAMinimumTheFirstSegmentIsASentence() {
        val chunker = SentenceChunker()
        val segments = chunker.push(VoiceText("Here is the short answer to your question, and the rest. Next")) + chunker.flush()
        assertEquals(listOf("Here is the short answer to your question, and the rest.", "Next"), segments.map { it.value })
    }

    @Test
    fun aMinimumMustBePositive() {
        assertFailsWith<IllegalArgumentException> { SentenceChunker(firstClauseMinLength = 0) }
    }

    private companion object {
        const val MIN = 40
    }
}
