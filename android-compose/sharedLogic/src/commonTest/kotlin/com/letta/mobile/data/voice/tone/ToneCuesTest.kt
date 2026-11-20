package com.letta.mobile.data.voice.tone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToneCuesTest {
    private fun stream(vararg deltas: String): List<TonePiece> {
        val tones = ToneCueStream()
        return deltas.flatMap { tones.push(it) } + tones.finish()
    }

    /** The stream's pieces as readable strings: words as they are, cues as `<module>` (`<>` for a reset). */
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
        assertEquals("Done.\nNext step.", ToneCues.strip("Done.\n[slow] Next step."))
    }

    @Test
    fun everyModuleIsACueAndNothingElseIs() {
        ToneCues.MODULES.forEach { module -> assertEquals(1, ToneCues.count("[${module.wireName}] Okay."), module.wireName) }
        listOf("softly", "slow, reassuring", "warm and breathy", "whispering", "x").forEach { words ->
            assertFalse(ToneCues.accepts(words), words)
            assertEquals("[$words] Okay.", ToneCues.strip("[$words] Okay."), "stays text: $words")
        }
    }

    @Test
    fun ordinaryBracketsAreNeverCues() {
        listOf(
            "[1] See the paper.",
            "[Warm] Capitals are not a cue.",
            "[warm](https://example.com) is a link.",
            "[warm][ref] is a reference link.",
            "[warm]: https://example.com",
            "[x] done",
            "[ ] not done",
            "- [warm] a list item",
            "Say [quiet] mid-sentence.",
            "Nested [[excited]] brackets.",
            "[warmer] is not a module.",
        ).forEach { text ->
            assertEquals(text, ToneCues.strip(text), "not a cue: $text")
            assertEquals(0, ToneCues.count(text), "not a cue: $text")
        }
    }

    @Test
    fun codeIsNeverReadForCues() {
        val span = "Use `[excited] x` here."
        val fence = "Example:\n```\n[excited] inside code\n```\n[warm] After."
        assertEquals(span, ToneCues.strip(span))
        assertEquals("Example:\n```\n[excited] inside code\n```\nAfter.", ToneCues.strip(fence))
    }

    @Test
    fun aCueAfterAQuotedSentenceEndStillStartsASentence() {
        assertEquals("He said \"go.\" Then we left.", ToneCues.strip("He said \"go.\" [quiet] Then we left."))
        assertEquals("A \"word\" [warm] stays.", ToneCues.strip("A \"word\" [warm] stays."))
    }

    @Test
    fun anUnfinishedModuleIsHeldWhileStreamingAndShownOnceItProvesNotToBeOne() {
        assertEquals("Hello. ", ToneCues.strip("Hello. [exci", streaming = true))
        assertEquals("Hello. ", ToneCues.strip("Hello. [excited]", streaming = true), "a link could still follow")
        assertEquals("Hello. [exci", ToneCues.strip("Hello. [exci", streaming = false))
        assertEquals("Hello. [softl", ToneCues.strip("Hello. [softl", streaming = true), "no module starts that way")
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
        assertEquals(listOf("[warm](https://x.y) now."), readable(stream("[war", "m](https://x.y) now.")))
        assertEquals(listOf("Hi. ", "[warmer] ok"), readable(stream("Hi. [warm", "er] ok")))
    }

    @Test
    fun neutralResetsTheTone() {
        assertEquals(listOf("<excited>", "Yes! ", "<>", "Back to normal."), readable(stream("[excited] Yes! [neutral] Back to normal.")))
    }

    @Test
    fun aTaggedSegmentReadsBackItsModuleAndWords() {
        val tagged = ToneCues.tag(ToneCue(ToneModule.QUIET), "Good night.")
        val (cue, words) = ToneCues.untag(tagged)
        assertEquals("quiet", cue?.instructions)
        assertEquals("Good night.", words)
        assertEquals(null to "Plain.", ToneCues.untag(ToneCues.tag(null, "Plain.")))
        assertTrue(ToneCues.accepts("sombre"))
    }

    @Test
    fun aModuleIsNamedOnlyByItsExactWireName() {
        ToneModule.entries.forEach { module -> assertEquals(module, ToneModule.named(module.wireName)) }
        listOf("Warm", " warm", "warmly", "").forEach { words -> assertNull(ToneModule.named(words), words) }
        assertTrue(ToneCue(ToneModule.NEUTRAL).resets)
        assertFalse(ToneCue(ToneModule.WARM).resets)
    }

    @Test
    fun aCueNeverPrintsItsName() {
        assertNull(Regex("excited").find(ToneCue(ToneModule.EXCITED).toString()))
        assertNull(Regex("excited").find(TonePiece.Cue(ToneCue(ToneModule.EXCITED)).toString()))
    }
}
