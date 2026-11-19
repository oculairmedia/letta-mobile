package com.letta.mobile.data.voice.tone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToneCuesTest {
    private fun stream(vararg deltas: String): List<TonePiece> {
        val tones = ToneCueStream()
        return deltas.flatMap { tones.push(it) } + tones.finish()
    }

    /** The stream's pieces as readable strings: words as they are, cues as `<cue>` (`<>` for a reset). */
    private fun readable(pieces: List<TonePiece>): List<String> = pieces.map {
        when (it) {
            is TonePiece.Words -> it.text
            is TonePiece.Cue -> "<${it.cue?.instructions.orEmpty()}>"
        }
    }

    @Test
    fun aCueAtTheStartIsRemovedFromWhatIsShown() {
        assertEquals("We won.", ToneCues.strip("[excited] We won."))
    }

    @Test
    fun aCueBeforeALaterSentenceIsRemovedToo() {
        assertEquals("Hello. We won!", ToneCues.strip("Hello. [excited] We won!"))
        assertEquals("Done.\nNext step.", ToneCues.strip("Done.\n[slow, reassuring] Next step."))
    }

    @Test
    fun cuesOfLettersSpacesCommasAndHyphensAreRead() {
        assertEquals(1, ToneCues.count("[slow, reassuring] Okay."))
        assertEquals(1, ToneCues.count("[half-whispering] Okay."))
        assertEquals(1, ToneCues.count("[${"a".repeat(ToneCues.MAX_CUE_LENGTH)}] Okay."))
    }

    @Test
    fun ordinaryBracketsAreNeverCues() {
        listOf(
            "[1] See the paper.",
            "[Excited] Capitals are not a cue.",
            "[see docs](https://example.com) for more.",
            "[docs][ref] are linked.",
            "[ref]: https://example.com",
            "[x] done",
            "[ ] not done",
            "- [x] a task",
            "Say [softly] mid-sentence.",
            "Nested [[excited]] brackets.",
            "[${"a".repeat(ToneCues.MAX_CUE_LENGTH + 1)}] too long.",
            "[note_1] has other characters.",
        ).forEach { text ->
            assertEquals(text, ToneCues.strip(text), "not a cue: $text")
            assertEquals(0, ToneCues.count(text), "not a cue: $text")
        }
    }

    @Test
    fun codeIsNeverReadForCues() {
        val span = "Use `[excited] x` here."
        val fence = "Example:\n```\n[excited] inside code\n```\n[calm] After."
        assertEquals(span, ToneCues.strip(span))
        assertEquals("Example:\n```\n[excited] inside code\n```\nAfter.", ToneCues.strip(fence))
    }

    @Test
    fun aCueAfterAQuotedSentenceEndStillStartsASentence() {
        assertEquals("He said \"go.\" Then we left.", ToneCues.strip("He said \"go.\" [quietly] Then we left."))
        assertEquals("A \"word\" [calm] stays.", ToneCues.strip("A \"word\" [calm] stays."))
    }

    @Test
    fun anUnfinishedCueIsHeldWhileStreamingAndShownOnceItProvesNotToBeOne() {
        assertEquals("Hello. ", ToneCues.strip("Hello. [exci", streaming = true))
        assertEquals("Hello. ", ToneCues.strip("Hello. [excited]", streaming = true), "a link could still follow")
        assertEquals("Hello. [exci", ToneCues.strip("Hello. [exci", streaming = false))
        assertEquals("Hello. [excited!", ToneCues.strip("Hello. [excited!", streaming = true))
    }

    @Test
    fun aStreamedCueIsNeverPassedOnAsWords() {
        assertEquals(
            listOf("Hello. ", "<excited>", "We won."),
            readable(stream("Hello. [ex", "cit", "ed] We won.")),
        )
    }

    @Test
    fun aHeldBracketThatIsNotACueIsPassedOnAsWords() {
        assertEquals(listOf("[see](https://x.y) now."), readable(stream("[see", "](https://x.y) now.")))
        assertEquals(listOf("Hi. ", "[note!] ok"), readable(stream("Hi. [note", "!] ok")))
    }

    @Test
    fun neutralResetsTheTone() {
        assertEquals(listOf("<excited>", "Yes! ", "<>", "Back to normal."), readable(stream("[excited] Yes! [neutral] Back to normal.")))
    }

    @Test
    fun aTaggedSegmentReadsBackItsCueAndWords() {
        val tagged = ToneCues.tag(ToneCue("softly"), "Good night.")
        val (cue, words) = ToneCues.untag(tagged)
        assertEquals("softly", cue?.instructions)
        assertEquals("Good night.", words)
        assertEquals(null to "Plain.", ToneCues.untag(ToneCues.tag(null, "Plain.")))
    }

    @Test
    fun aCueNeverPrintsItsWords() {
        assertNull(Regex("excited").find(ToneCue("excited").toString()))
        assertNull(Regex("excited").find(TonePiece.Cue(ToneCue("excited")).toString()))
    }
}
