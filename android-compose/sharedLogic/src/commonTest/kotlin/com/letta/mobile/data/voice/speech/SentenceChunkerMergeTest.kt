package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sentences after the first are merged into longer segments, so the speech server gets fewer, steadier requests. */
class SentenceChunkerMergeTest {
    private fun merged(
        vararg deltas: String,
        minimum: Int? = MIN,
        max: Int = SentenceChunker.DEFAULT_MAX_SEGMENT_LENGTH,
        firstClause: Int? = null,
    ): List<String> {
        val chunker = SentenceChunker(max, firstClause, minimum)
        val pushed = deltas.flatMap { chunker.push(VoiceText(it)) }
        return (pushed + chunker.flush()).map { it.value }
    }

    @Test
    fun shortSentencesAfterTheFirstAreMergedUpToTheMinimum() {
        assertEquals(
            listOf("First one here.", "Second is short. Third is short too.", "Fourth sentence ends here. Tail."),
            merged("First one here. Second is short. Third is short too. Fourth sentence ends here. Tail."),
        )
    }

    @Test
    fun theFirstSegmentIsTheSameWithAndWithoutMerging() {
        val text = "Yes. That is right, and here is a long clause after it. Another short one."
        assertEquals(merged(text, minimum = null).first(), merged(text).first())
        val early = "Here is the short answer to your question, and then the rest of it. Then a second sentence."
        assertEquals(merged(early, minimum = null, firstClause = 20).first(), merged(early, firstClause = 20).first())
    }

    @Test
    fun theFirstSentenceIsReleasedAloneAsSoonAsItEnds() {
        val chunker = SentenceChunker(minSegmentLength = MIN)
        assertEquals(listOf("Hi there."), chunker.push(VoiceText("Hi there. Next")).map { it.value })
    }

    @Test
    fun aMergedSegmentIsReleasedAsSoonAsItReachesTheMinimumNotLater() {
        val chunker = SentenceChunker(minSegmentLength = MIN)
        chunker.push(VoiceText("Hi there. "))
        assertTrue(chunker.push(VoiceText("Second is short. ")).isEmpty(), "below the minimum: held for more sentences")
        assertEquals(
            listOf("Second is short. Third is short too."),
            chunker.push(VoiceText("Third is short too. ")).map { it.value },
        )
    }

    @Test
    fun flushReleasesWhatIsHeldBelowTheMinimum() {
        val chunker = SentenceChunker(minSegmentLength = MIN)
        chunker.push(VoiceText("Hi there. Second is short. "))
        assertEquals(listOf("Second is short."), chunker.flush().map { it.value })
        assertTrue(chunker.flush().isEmpty())
    }

    @Test
    fun noMergedSegmentExceedsTheMaximum() {
        val sentence = "x".repeat(59) + "."
        val segments = merged("Hi there. $sentence $sentence $sentence $sentence", minimum = 90, max = 100)
        assertEquals(listOf("Hi there.", sentence, sentence, sentence, sentence), segments)
        val wide = merged("Hi there. $sentence $sentence $sentence", minimum = 100, max = 130)
        assertTrue(wide.all { it.length <= 130 }, "lengths ${wide.map { it.length }}")
        assertEquals("Hi there. $sentence $sentence $sentence", wide.joinToString(" "))
    }

    @Test
    fun aMinimumAboveTheMaximumIsCappedByTheMaximum() {
        val sentence = "y".repeat(39) + "."
        val segments = merged("Hi there. $sentence $sentence $sentence", minimum = 500, max = 90)
        assertEquals(listOf("Hi there.", "$sentence $sentence", sentence), segments)
    }

    @Test
    fun aFullSizeSentenceIsReleasedEvenWhenTheMinimumIsAboveTheMaximum() {
        val full = "z".repeat(89) + "."
        val chunker = SentenceChunker(maxSegmentLength = 90, minSegmentLength = 500)
        chunker.push(VoiceText("Hi there. "))
        assertEquals(listOf(full), chunker.push(VoiceText("$full Next")).map { it.value }, "a segment at the maximum goes out at once")
    }

    @Test
    fun mergingNeverCrossesAFencedCodeBlock() {
        assertEquals(
            listOf("Alpha one.", "Beta two.", "Gamma three. Delta four."),
            merged("Alpha one. Beta two.\n```\ncode. here\n```\nGamma three. Delta four.", minimum = 100),
        )
    }

    @Test
    fun withoutAMinimumEverySentenceIsItsOwnSegment() {
        assertEquals(listOf("One.", "Two.", "Three."), merged("One. Two. Three.", minimum = null))
    }

    @Test
    fun resetDropsWhatWasHeld() {
        val chunker = SentenceChunker(minSegmentLength = MIN)
        chunker.push(VoiceText("Hi there. Second is short. "))
        chunker.reset()
        assertTrue(chunker.flush().isEmpty())
    }

    private companion object {
        const val MIN = 30
    }
}
