package com.letta.mobile.data.voice.tone

import kotlin.jvm.JvmInline

/** One of the closed set of mood modules a tone cue may select, by the [wireName] a reply and a speech request use. */
enum class ToneModule(
    /** The module's name in a cue (`[warm]`) and in a speech request's `instructions`. */
    val wireName: String,
) {
    NEUTRAL("neutral"),
    WARM("warm"),
    EXCITED("excited"),
    SOMBRE("sombre"),
    QUIET("quiet"),
    FAST("fast"),
    SLOW("slow"),
    ;

    /** The module names. */
    companion object {
        /** The module named [wireName] exactly; null for anything else. */
        fun named(wireName: String): ToneModule? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * One tone cue: the mood module the following sentences use ([module], such as [ToneModule.WARM]),
 * sent to a speech server as its `instructions`. Its [toString] carries no text.
 */
@JvmInline
value class ToneCue(
    /** The module, never logged. */
    val module: ToneModule,
) {
    /** The module's name, as the speech request's `instructions` carry it. */
    val instructions: String get() = module.wireName

    /** Whether this cue resets the tone (`[neutral]`): what follows it carries no instructions. */
    val resets: Boolean get() = module == ToneModule.NEUTRAL

    override fun toString(): String = "ToneCue(length=${instructions.length})"
}

/** Where a reply's text goes: words to show and speak, or a change of tone. */
sealed interface TonePiece {
    /** Words, with every cue removed. */
    data class Words(
        /** The words. */
        val text: String,
    ) : TonePiece {
        override fun toString(): String = "Words(length=${text.length})"
    }

    /** From here on the reply sounds like [cue]; null after `[neutral]`. */
    data class Cue(
        /** The tone now in force, or null for none. */
        val cue: ToneCue?,
    ) : TonePiece
}

/**
 * Inline tone cues in a reply: `[module]` at the start of a sentence (the start of the text or of a
 * line, or after a sentence's end and whitespace), where `module` is one of the closed set of mood
 * modules ([MODULES]: `neutral`, `warm`, `excited`, `sombre`, `quiet`, `fast`, `slow`). A cue
 * applies to its sentence and every later one until the next cue or the end of the reply;
 * `[neutral]` resets it. A cue only selects a module: it never carries a description of a voice,
 * which comes from the agent's voice core, never from the reply.
 *
 * Anything else in brackets is not a cue and stays text: a citation (`[1]`), a link (`[warm](url)`,
 * `[warm][ref]` or a reference definition `[warm]: url`), a task box, a word outside the set
 * (`[softly]`), anything mid-sentence or after a list marker, anything nested in another bracket, and
 * anything inside a code span or fence.
 *
 * This is the one place that reads cues: the chat timeline and the dock show [strip]'s text, and voice
 * mode speaks what [ToneCueStream] passes on, each sentence with its module sent as `instructions`.
 */
object ToneCues {
    /** The mood modules a cue may select; nothing else is a cue. */
    val MODULES: Set<ToneModule> = ToneModule.entries.toSet()

    /** The longest module name, in characters between the brackets. */
    internal val MAX_CUE_LENGTH: Int = MODULES.maxOf { it.wireName.length }

    /** [text] as it should be shown: every cue removed (with the space after it). */
    fun strip(text: String): String = strip(text, streaming = false)

    /**
     * [text] as it should be shown; while [streaming], an unfinished cue at the end (`[exci`, or a
     * whole cue whose next character could still make it a link) is held back too, so it never
     * flashes on screen.
     */
    fun strip(text: String, streaming: Boolean): String {
        if (text.indexOf('[') < 0) return text
        val scan = ToneScan(text, streaming)
        return buildString { scan.tokens.forEach { if (it is ToneToken.Words) append(text, it.start, it.end) } }
    }

    /** How many cues [text] holds; for counting only. */
    fun count(text: String): Int = if (text.indexOf('[') < 0) 0 else ToneScan(text, false).tokens.count { it is ToneToken.Cue }

    /**
     * Whether [words] select a mood module: exactly one of [MODULES]. The host applies the same rule
     * to the `instructions` a speech request carries, so nothing but a module name reaches the server.
     */
    fun accepts(words: String): Boolean = ToneModule.named(words) != null

    /** [words] marked with [cue] at their start, in the cue syntax; [words] alone when [cue] is null. */
    fun tag(cue: ToneCue?, words: String): String = if (cue == null) words else "[${cue.instructions}] $words"

    /** The cue a [tag]ged text starts with (null when none) and the words after it. */
    fun untag(text: String): Pair<ToneCue?, String> {
        if (!text.startsWith('[')) return null to text
        val first = ToneScan(text, false).tokens.firstOrNull() as? ToneToken.Cue ?: return null to text
        if (first.start != 0) return null to text
        return first.cue to text.substring(first.end)
    }
}

/**
 * Follows one reply's text as it streams and passes on its words and its changes of tone in order,
 * holding back an unfinished cue until it closes or proves not to be one. Not thread-safe: one stream
 * serves one reply.
 */
class ToneCueStream {
    private val text = StringBuilder()
    private var passed = 0

    /** Appends [delta] and returns what is now decided. */
    fun push(delta: String): List<TonePiece> {
        text.append(delta)
        return drain(streaming = true)
    }

    /** The reply is complete: returns everything still held. */
    fun finish(): List<TonePiece> = drain(streaming = false)

    /** Passes on every token not passed on yet; held text is in no token until it is decided. */
    private fun drain(streaming: Boolean): List<TonePiece> {
        val all = text.toString()
        val out = mutableListOf<TonePiece>()
        for (token in ToneScan(all, streaming).tokens) {
            if (token.end <= passed) continue
            out += when (token) {
                is ToneToken.Words -> TonePiece.Words(all.substring(maxOf(token.start, passed), token.end))
                is ToneToken.Cue -> TonePiece.Cue(token.cue.takeUnless { it.resets })
            }
            passed = token.end
        }
        return out
    }
}

/** A run of a reply's text: words, or a cue (with the one space after it). */
private sealed interface ToneToken {
    val start: Int
    val end: Int

    data class Words(override val start: Int, override val end: Int) : ToneToken

    data class Cue(override val start: Int, override val end: Int, val cue: ToneCue) : ToneToken
}

/**
 * One pass over [text]: its words and cues in order. While [streaming], an unfinished cue at the end
 * is in no token (held back until it is decided). Code spans and fences are passed over as words.
 */
private class ToneScan(private val text: String, private val streaming: Boolean) {
    val tokens = mutableListOf<ToneToken>()

    private var wordsFrom = 0
    private var fence = false
    private var span = false

    init {
        var index = 0
        while (index < text.length) index = step(index)
        if (wordsFrom < text.length) tokens += ToneToken.Words(wordsFrom, text.length)
    }

    /** Reads what starts at [index] (a fence, a code span mark, a possible cue or a plain character); returns where scanning goes on. */
    private fun step(index: Int): Int = when {
        opensFence(index) -> index.also { fence = !fence } + FENCE.length
        marksSpan(index) -> index.also { span = !span } + 1
        mayOpenCue(index) -> cueAt(index)
        else -> index + 1
    }

    private fun opensFence(index: Int): Boolean = !span && text.startsWith(FENCE, index)

    private fun marksSpan(index: Int): Boolean = !fence && text[index] == '`'

    /** A `[` outside code, at the start of a sentence. */
    private fun mayOpenCue(index: Int): Boolean = !inCode() && text[index] == '[' && text.startsSentenceAt(index)

    private fun inCode(): Boolean = fence || span

    /** Reads a bracket opening at [open] that may be a cue; returns where scanning goes on. */
    private fun cueAt(open: Int): Int {
        val close = readCueWord(open)
        if (close >= text.length) return close.also { if (streaming && couldBecomeCue(open, close)) hold(open) }
        val module = closedModule(open, close) ?: return open + 1
        return closedCue(open, close + 1, module)
    }

    /** Where the lowercase letters after [open] stop, reading no further than a module name could reach. */
    private fun readCueWord(open: Int): Int {
        var close = open + 1
        while (close < text.length && continuesCueWord(open, close)) close++
        return close
    }

    private fun continuesCueWord(open: Int, at: Int): Boolean = at - open - 1 <= ToneCues.MAX_CUE_LENGTH && text[at] in 'a'..'z'

    /** The module the bracket's words name when it closes at [close]; null otherwise. */
    private fun closedModule(open: Int, close: Int): ToneModule? {
        if (text[close] != ']') return null
        return ToneModule.named(text.substring(open + 1, close))
    }

    /**
     * A bracketed [module] from [open] to [after]: a cue unless a link follows it (and, while
     * streaming, held when nothing follows it yet); returns where scanning goes on.
     */
    private fun closedCue(open: Int, after: Int, module: ToneModule): Int {
        val next = text.getOrNull(after)
        if (next == null && streaming) return after.also { hold(open) }
        if (next in LINK_FOLLOWERS) return after
        val end = if (next == ' ') after + 1 else after
        if (open > wordsFrom) tokens += ToneToken.Words(wordsFrom, open)
        tokens += ToneToken.Cue(open, end, ToneCue(module))
        wordsFrom = end
        return end
    }

    /** Whether the unfinished bracket from [open] to [close] is still the start of a module name. */
    private fun couldBecomeCue(open: Int, close: Int): Boolean {
        val typed = text.substring(open + 1, close)
        return ToneCues.MODULES.any { it.wireName.startsWith(typed) }
    }

    /** The rest of the text, from [open], may still become a cue: it is held back, in no token. */
    private fun hold(open: Int) {
        if (open > wordsFrom) tokens += ToneToken.Words(wordsFrom, open)
        wordsFrom = text.length
    }

    private companion object {
        const val FENCE = "```"
        val LINK_FOLLOWERS = setOf('(', '[', ':')
    }
}

/**
 * Whether [index] starts a sentence in this text: the start of the text or a line, or after a
 * sentence's end (a terminator, with any closing quotes or brackets after it) followed by whitespace.
 */
private fun String.startsSentenceAt(index: Int): Boolean {
    if (atLineStart(index - 1)) return true
    if (!this[index - 1].isWhitespace()) return false
    val beforeSpace = lastBeforeSpaces(index - 1) ?: return true
    return endsSentenceAt(beforeSpace)
}

/** Whether the character before a position, at [before], is the start of the text or a line break. */
private fun String.atLineStart(before: Int): Boolean = before < 0 || this[before] == '\n'

/**
 * The position of the last character before the whitespace ending at [from], or null when that
 * whitespace holds a line break or reaches the start of the text (both start a sentence).
 */
private fun String.lastBeforeSpaces(from: Int): Int? {
    var before = from
    while (before >= 0 && this[before].isWhitespace()) {
        if (this[before] == '\n') return null
        before--
    }
    return before.takeIf { it >= 0 }
}

/** Whether the text up to [last] ends a sentence: a terminator, then any closing quotes or brackets. */
private fun String.endsSentenceAt(last: Int): Boolean {
    var before = last
    while (before >= 0 && this[before] in SENTENCE_CLOSERS) before--
    return before < 0 || this[before] in SENTENCE_TERMINATORS
}

private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '…')
private val SENTENCE_CLOSERS = setOf('"', '\'', ')', ']', '*', '_', '”', '’')
