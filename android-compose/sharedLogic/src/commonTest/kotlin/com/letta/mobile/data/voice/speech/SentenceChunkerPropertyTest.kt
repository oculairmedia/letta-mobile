package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText
import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** One building block of a generated reply. */
private enum class Fragment(val text: String) {
    WORD("hello"),
    WORDS("good morning"),
    SENTENCE_END(". "),
    QUESTION("? "),
    BOLD_OPEN("<b>"),
    BOLD_CLOSE("</b>"),
    ANCHOR_OPEN("<a href=\"https://x.io/a.b\">"),
    ANCHOR_CLOSE("</a>"),
    UNFINISHED_TAG("<span title=\"a. b"),
    LINK("[text](https://x.io/a.b)"),
    UNFINISHED_LINK("[text](https://x.io/"),
    COMPARISON("2 < 3"),
    EMOJI("🙂"),
    NEWLINE("\n"),
}

/** The segment lengths every case is run with (16 is the chunker's minimum). */
private enum class Limit(val chars: Int) { SMALL(16), MEDIUM(40), LARGE(200) }

/** Whether a generated reply ends with a flush (a finished reply) or stops mid-stream. */
private enum class Ending { FLUSHED, OPEN }

/** What closes a markup region in the oracle. */
private enum class Closer(val char: Char) { TAG('>'), TARGET(')') }

/** Text of a generated reply or one of its deltas. */
@JvmInline
private value class ReplyText(val value: String)

/** The seed a case was generated from, printed on failure so it can be replayed. */
@JvmInline
private value class CaseSeed(val value: Int)

/** One generated case: the reply, how it is split into deltas, and how it ends. */
private class GeneratedCase(val seed: CaseSeed, val reply: ReplyText, val deltas: List<ReplyText>, val ending: Ending) {
    override fun toString(): String =
        "seed=${seed.value} ending=$ending reply=${reply.quoted()} deltas=${deltas.map { it.quoted() }}"

    private fun ReplyText.quoted(): String = "\"" + value.replace("\n", "\\n") + "\""
}

/** Builds a case from a seed: 1-8 fragments, sometimes cut short mid-fragment, split at random positions. */
private class CaseGenerator(private val seed: CaseSeed) {
    private val random = Random(seed.value)

    fun next(): GeneratedCase {
        val whole = (1..random.nextInt(1, MAX_FRAGMENTS + 1)).joinToString("") { Fragment.entries.random(random).text }
        val reply = ReplyText(if (random.nextBoolean()) whole.take(random.nextInt(1, whole.length + 1)) else whole)
        val ending = if (random.nextBoolean()) Ending.FLUSHED else Ending.OPEN
        return GeneratedCase(seed, reply, split(reply), ending)
    }

    private fun split(reply: ReplyText): List<ReplyText> {
        val length = reply.value.length
        val cuts = (1..random.nextInt(0, MAX_CUTS + 1)).map { random.nextInt(0, length + 1) }.distinct().sorted()
        val bounds = listOf(0) + cuts + length
        return bounds.zipWithNext { from, to -> ReplyText(reply.value.substring(from, to)) }.filter { it.value.isNotEmpty() }
    }

    private companion object {
        const val MAX_FRAGMENTS = 8
        const val MAX_CUTS = 5
    }
}

/**
 * The oracle: the words a listener should hear from a whole reply, computed independently of the
 * chunker with the same markup grammar. A tag runs from `<` followed by a letter or `/` to the next
 * `>` (or the end, when unfinished) and is silent. A link `[text](target)` is heard as its text; an
 * unfinished link (no `]`, or no `)`) as its text so far; the target is silent. Everything else is
 * heard. Only letters and digits are compared, so punctuation and spacing choices do not matter, but
 * any word of markup that leaks (a URL, an attribute) or any lost word fails.
 */
private class HeardWords(private val reply: ReplyText) {
    private val text = reply.value
    private val heard = StringBuilder()
    private var index = 0

    fun words(): String {
        while (index < text.length) step()
        return heard.lettersAndDigits()
    }

    private fun step() {
        when {
            atTagStart() -> skipPast(Closer.TAG)
            text[index] == '[' -> link()
            else -> heard.append(text[index++])
        }
    }

    private fun link() {
        val close = text.indexOf(']', index).takeIf { it >= 0 } ?: text.length
        heard.append(text, index + 1, close)
        index = (close + 1).coerceAtMost(text.length)
        if (text.getOrNull(index) == '(') skipPast(Closer.TARGET)
    }

    private fun skipPast(closer: Closer) {
        val close = text.indexOf(closer.char, index)
        index = if (close < 0) text.length else close + 1
    }

    private fun atTagStart(): Boolean {
        if (text[index] != '<') return false
        val next = text.getOrNull(index + 1) ?: return false
        return next.isLetter() || next == '/'
    }
}

private fun CharSequence.lettersAndDigits(): String = filter { it.isLetterOrDigit() }.toString()

class SentenceChunkerPropertyTest {
    private val rawMarkup = Regex("""<[A-Za-z/]|]\(|\[|http""")

    private fun spoken(case: GeneratedCase, limit: Limit): List<String> {
        val chunker = SentenceChunker(limit.chars)
        val pushed = case.deltas.flatMap { chunker.push(VoiceText(it.value)) }
        val flushed = if (case.ending == Ending.FLUSHED) chunker.flush() else emptyList()
        return (pushed + flushed).map { it.value }
    }

    /** The first violation for [case] at [limit], or null. */
    private fun violation(case: GeneratedCase, limit: Limit): String? {
        val segments = spoken(case, limit)
        segments.firstOrNull { rawMarkup.containsMatchIn(it) }?.let { return "raw markup in ${it.trim()}" }
        if (case.ending == Ending.OPEN) return null
        val heard = segments.joinToString(" ").lettersAndDigits()
        val expected = HeardWords(case.reply).words()
        return if (heard == expected) null else "heard $heard expected $expected"
    }

    @Test
    fun noRawMarkupIsEverSpokenAndNoWordIsLost() {
        val failures = (0 until CASES).flatMap { seed ->
            val case = CaseGenerator(CaseSeed(seed)).next()
            Limit.entries.mapNotNull { limit -> violation(case, limit)?.let { "$case limit=${limit.chars}: $it" } }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${CASES * Limit.entries.size} failed, first: ${failures.first()}")
    }

    @Test
    fun theComparisonKeepsItsLessThanSign() {
        val chunker = SentenceChunker(Limit.SMALL.chars)
        val segments = chunker.push(VoiceText("Is 2 < 3 true")) + chunker.flush()
        assertEquals(listOf("Is 2 < 3 true"), segments.map { it.value })
    }

    @Test
    fun unfinishedMarkupAtTheEndOfAReplyIsNeverSpoken() {
        val cases = mapOf("Hello <span" to "Hello", "See [docs](https://x.io/" to "See docs")
        cases.forEach { (reply, heard) ->
            val chunker = SentenceChunker(Limit.SMALL.chars)
            val segments = chunker.push(VoiceText(reply)) + chunker.flush()
            assertEquals(heard, segments.joinToString(" ") { it.value }, reply)
        }
    }

    private companion object {
        const val CASES = 300
    }
}
