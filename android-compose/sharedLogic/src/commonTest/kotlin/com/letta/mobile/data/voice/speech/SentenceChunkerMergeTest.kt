package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sentences after the first are merged into longer segments, so the speech server gets fewer, steadier requests. */
class SentenceChunkerMergeTest {
    private fun merged(
        vararg deltas: String,
        minimum: Int? = MIN,
        max: Int = SentenceChunker.DEFAULT_MAX_SEGMENT_LENGTH,
        tail: Int? = null,
    ): List<String> = mergedWith(SentenceChunker(max, null, minimum, tail), *deltas)

    private fun mergedWith(chunker: SentenceChunker, vararg deltas: String): List<String> {
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
        assertEquals(
            mergedWith(SentenceChunker(DEFAULT_MAX, 20, null), early).first(),
            mergedWith(SentenceChunker(DEFAULT_MAX, 20, MIN), early).first(),
        )
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

    @Test
    fun theDefaultLongestSegmentMatchesTheSpeechServersRequestSize() {
        assertEquals(400, SentenceChunker.DEFAULT_MAX_SEGMENT_LENGTH)
        val sentence = "w".repeat(59) + "."
        val text = (1..12).joinToString(" ") { sentence }
        val lengths = merged(text, minimum = 200, max = 400).map { it.length }
        assertEquals(listOf(60, 243, 243, 182), lengths, "first alone, then four sentences (243) reach the 200 minimum, well inside 400")
        val wide = merged(text, minimum = 380, max = 400).map { it.length }
        assertEquals(listOf(60, 365, 304), wide, "six sentences (365) fit in 400; a seventh (426) would not")
    }

    @Test
    fun aTinyTailJoinsTheSegmentBeforeItInsteadOfGoingOutAlone() {
        val text = "Hi there. $LONG_SECOND Ok."
        assertEquals(listOf("Hi there.", LONG_SECOND, "Ok."), merged(text, tail = null), "without absorption the tail is alone")
        assertEquals(listOf("Hi there.", "$LONG_SECOND Ok."), merged(text, tail = TAIL))
    }

    @Test
    fun aTailJoinsAsItStreamsInAndTheSegmentWaitsForIt() {
        val chunker = SentenceChunker(minSegmentLength = MIN, tailAbsorbLength = TAIL)
        assertEquals(listOf("Hi there."), chunker.push(VoiceText("Hi there. $LONG_SECOND ")).map { it.value })
        assertEquals(listOf("$LONG_SECOND Ok."), chunker.push(VoiceText("Ok. ")).map { it.value })
        assertTrue(chunker.flush().isEmpty())
        assertEquals(1, chunker.tailsAbsorbed)
    }

    @Test
    fun aSegmentAtTheMinimumIsReleasedByTheFlushWhenNoTailComes() {
        val chunker = SentenceChunker(minSegmentLength = MIN, tailAbsorbLength = TAIL)
        chunker.push(VoiceText("Hi there. $LONG_SECOND "))
        assertEquals(listOf(LONG_SECOND), chunker.flush().map { it.value })
        assertEquals(0, chunker.tailsAbsorbed)
    }

    @Test
    fun aSentenceAsLongAsTheTailLengthIsNotATail() {
        val tail = "t".repeat(TAIL - 1) + "."
        assertEquals(listOf("Hi there.", LONG_SECOND, tail), merged("Hi there. $LONG_SECOND $tail", tail = TAIL))
        val shorter = "t".repeat(TAIL - 2) + "."
        assertEquals(listOf("Hi there.", "$LONG_SECOND $shorter"), merged("Hi there. $LONG_SECOND $shorter", tail = TAIL))
    }

    @Test
    fun aTailNeverTakesASegmentPastTheMaximum() {
        val nearFull = "n".repeat(97) + "."
        assertEquals(
            listOf("Hi there.", nearFull, "Ok."),
            merged("Hi there. $nearFull Ok.", minimum = 50, max = 100, tail = TAIL),
            "98 + 1 + 3 would be 102",
        )
        val room = "r".repeat(95) + "."
        assertEquals(listOf("Hi there.", "$room Ok."), merged("Hi there. $room Ok.", minimum = 50, max = 100, tail = TAIL))
    }

    @Test
    fun aTailNeverJoinsAcrossAFencedCodeBlock() {
        assertEquals(
            listOf("Hi there.", LONG_SECOND, "Ok."),
            merged("Hi there. $LONG_SECOND\n```\ncode. here\n```\nOk.", tail = TAIL),
        )
    }

    @Test
    fun aTailNeverJoinsAcrossAFlush() {
        val chunker = SentenceChunker(minSegmentLength = MIN, tailAbsorbLength = TAIL)
        val before = chunker.push(VoiceText("Hi there. $LONG_SECOND ")) + chunker.flush()
        val after = chunker.push(VoiceText("Ok. ")) + chunker.flush()
        assertEquals(listOf("Hi there.", LONG_SECOND), before.map { it.value })
        assertEquals(listOf("Ok."), after.map { it.value })
    }

    @Test
    fun theFirstSegmentIsTheSameWithAndWithoutTailAbsorption() {
        listOf("Ok. $LONG_SECOND Ok.", "Hi there. $LONG_SECOND Ok.", "Just one. ").forEach { text ->
            assertEquals(merged(text, tail = null).first(), merged(text, tail = TAIL).first(), text)
        }
        val early = "Here is the short answer to your question, and then the rest of it. $LONG_SECOND Ok."
        assertEquals(
            mergedWith(SentenceChunker(DEFAULT_MAX, 20, MIN, null), early).first(),
            mergedWith(SentenceChunker(DEFAULT_MAX, 20, MIN, TAIL), early).first(),
        )
    }

    @Test
    fun aTailAfterALateReplysFirstSentenceStillJoinsTheSegmentBeforeIt() {
        val chunker = SentenceChunker(minSegmentLength = MIN, tailAbsorbLength = TAIL)
        chunker.push(VoiceText("Hi there. "))
        chunker.flush()
        chunker.startNewPart()
        val released = chunker.push(VoiceText("Research is done. $LONG_SECOND Ok. ")) + chunker.flush()
        assertEquals(listOf("Research is done.", "$LONG_SECOND Ok."), released.map { it.value })
    }

    @Test
    fun aNonPositiveTailLengthIsRejected() {
        assertFailsWith<IllegalArgumentException> { SentenceChunker(tailAbsorbLength = 0) }
    }

    private companion object {
        const val MIN = 30
        const val DEFAULT_MAX = SentenceChunker.DEFAULT_MAX_SEGMENT_LENGTH
        const val TAIL = 10
        const val LONG_SECOND = "This second sentence is comfortably long enough."
    }
}
