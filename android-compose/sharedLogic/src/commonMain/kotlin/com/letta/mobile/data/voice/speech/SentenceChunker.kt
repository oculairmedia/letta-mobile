package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText

/**
 * Turns a stream of reply text deltas into speakable segments, so speech can start before the
 * reply is complete.
 *
 * A segment ends at a sentence terminator followed by whitespace (not after a known abbreviation,
 * a single initial or a list number), or at a line break. Fenced code blocks are skipped; markdown,
 * links, URLs and HTML are stripped by [SpeakableText]. A segment never exceeds [SegmentSizing.maxSegmentLength]:
 * longer text is cut at the last clause break or space. [flush] releases whatever is left.
 *
 * With [SegmentSizing.firstClauseMinLength] set, the chunker's first segment is released early, at the first
 * clause break (a comma, semicolon, colon or dash followed by whitespace) once that clause is at
 * least that long, or at the first sentence end, whichever comes first; speech then starts before
 * the first sentence is complete. The sentence-end guards (abbreviations, initials, list numbers)
 * still apply, and a clause break never splits a number (`1,000`, `10:30`) or follows an
 * abbreviation (`e.g.,`).
 *
 * With [SegmentSizing.minSegmentLength] set, every segment after the first (or after [startNewPart]) merges the sentences that follow it
 * (joined with a space) until it is at least that long, so a speech server gets fewer, longer
 * requests and its voice drifts less from one segment to the next. The first segment is never held
 * for this, a merged segment goes out the moment it reaches the minimum (or at [flush]) unless
 * [SegmentSizing.tailAbsorbLength] is set, a merged segment never exceeds [SegmentSizing.maxSegmentLength], and nothing merges
 * across a fenced code block or a [flush] (the caller flushes where the mood changes).
 *
 * With [SegmentSizing.tailAbsorbLength] as well, a segment that has reached the minimum waits for the next
 * sentence before it goes out: when that sentence is shorter than [SegmentSizing.tailAbsorbLength] and fits within
 * [SegmentSizing.maxSegmentLength] it joins the segment ([tailsAbsorbed] counts these), so a reply does not end on
 * a tiny segment, the one the voice drifts on most. The wait ends at the next sentence, a code
 * block or a [flush], so nothing waits on a pause in the reply beyond one sentence.
 *
 * Tail absorption belongs to merging: with no [SegmentSizing.minSegmentLength] every sentence is
 * its own segment and [SegmentSizing.tailAbsorbLength] has no effect.
 *
 * Not thread-safe: one chunker serves one reply.
 */
class SentenceChunker(private val sizing: SegmentSizing = SegmentSizing()) {
    private val maxSegmentLength = sizing.maxSegmentLength
    private val pending = StringBuilder()
    private var inCodeBlock = false
    private var dropUntil: Char? = null
    private val softCut = SoftCut(maxSegmentLength)
    private val merger = SegmentMerger(sizing)

    /** How many short tails were joined to the segment before them (over the whole reply). */
    val tailsAbsorbed: Int get() = merger.tailsAbsorbed

    /** Appends [delta] and returns every segment that is now complete. */
    fun push(delta: VoiceText): List<VoiceText> {
        pending.append(delta.value)
        return Drain(ScanMode.PARTIAL).run()
    }

    /** Returns every remaining segment, including an unfinished sentence, and resets. */
    fun flush(): List<VoiceText> {
        val segments = Drain(ScanMode.FINAL).run()
        reset()
        return segments
    }

    /**
     * The next segment starts a new stretch of speech (a late reply after the answer paused): it is
     * released on its own, as the first one was, and merging resumes after it. Whatever is held
     * should be taken with [flush] first.
     */
    fun startNewPart() {
        merger.startNewPart()
    }

    /** Drops everything buffered; the first segment, once released, stays released. */
    fun reset() {
        pending.clear()
        merger.clear()
        inCodeBlock = false
        dropUntil = null
    }

    /** The first [length] buffered characters become a segment; [skip] more after them are dropped. */
    private data class Cut(val length: Int, val skip: Int = 0)

    /** One pass over the buffer, collecting every segment that can be decided in [mode]. */
    private inner class Drain(private val mode: ScanMode) {
        private val out = mutableListOf<VoiceText>()

        fun run(): List<VoiceText> {
            do {
                val progressed = when {
                    inCodeBlock -> skipCode()
                    dropUntil != null -> skipDropped()
                    else -> splitProse()
                }
            } while (progressed)
            if (mode == ScanMode.FINAL) emitMerged()
            return out
        }

        private fun skipCode(): Boolean {
            val close = pending.indexOf(FENCE)
            if (close >= 0) {
                pending.deleteRange(0, close + FENCE.length)
                inCodeBlock = false
                return true
            }
            val keep = if (mode == ScanMode.FINAL) 0 else FENCE.length - 1
            if (pending.length > keep) pending.deleteRange(0, pending.length - keep)
            if (mode == ScanMode.FINAL) inCodeBlock = false
            return false
        }

        /** Drops the rest of markup released past the hold limit until it closes; nothing of it is spoken. */
        private fun skipDropped(): Boolean {
            val close = pending.indexOf(dropUntil ?: return false)
            if (close < 0) {
                pending.clear()
                if (mode == ScanMode.FINAL) dropUntil = null
                return false
            }
            pending.deleteRange(0, close + 1)
            dropUntil = null
            return true
        }

        private fun splitProse(): Boolean {
            val fence = pending.indexOf(FENCE)
            val limit = if (fence < 0) pending.length else fence
            val end = firstCut(SentenceScan(pending, limit, mode))
            val overflow = overflowCut()
            return when {
                end != null -> release(Cut(end.value))
                fence >= 0 -> openCodeBlock(Cut(fence, FENCE.length))
                overflow != null -> releaseOverflow(overflow)
                mode == ScanMode.FINAL -> emitAll()
                else -> false
            }
        }

        /** The first sentence end, or the first segment's early clause end when that comes sooner. */
        private fun firstCut(scan: SentenceScan): TextIndex? {
            val end = scan.firstEnd()
            val clause = earlyClause(scan) ?: return end
            return if (end == null || clause.value < end.value) clause else end
        }

        /** Where the first segment may end early, while nothing has been released yet. */
        private fun earlyClause(scan: SentenceScan): TextIndex? {
            val minimum = sizing.firstClauseMinLength ?: return null
            return if (merger.released) null else scan.firstClauseEnd(minimum)
        }

        /**
         * Overlong text without a sentence end is released up to its last break outside markup,
         * then cleaned and split to length; a link is never cut raw. Text with no safe break is
         * held until [HARD_LIMIT_FACTOR] times the segment length, then released whole.
         */
        private fun overflowCut(): Cut? {
            if (pending.length <= maxSegmentLength) return null
            val safe = softCut.lastSafeBreak(pending)
            if (safe != null) return Cut(safe)
            return if (pending.length > maxSegmentLength * HARD_LIMIT_FACTOR) Cut(pending.length) else null
        }

        /**
         * Releases overflowing text. Past the hold limit everything pending goes, so markup still open
         * there is settled first: an unfinished tag is dropped (the text before it is released, the
         * tag's remainder is skipped until it closes or the reply ends); an unfinished link's text
         * loses its `[` and stays text (its later `](target)` is dropped on release); an unfinished
         * link target is released as its link text and the rest of the target is skipped. A `<` at the
         * very end is held back: the next delta decides whether it starts a tag.
         */
        private fun releaseOverflow(cut: Cut): Boolean {
            if (cut.length != pending.length) return release(cut)
            if (pending.endsWith('<')) return release(Cut(pending.length - 1))
            val open = openMarkupIn(pending) ?: return release(cut)
            return when (open.closer) {
                '>' -> releaseThenDrop(Cut(open.start.value), '>')
                ']' -> {
                    pending.deleteAt(open.start.value)
                    true
                }
                else -> releaseThenDrop(cut, ')')
            }
        }

        private fun releaseThenDrop(cut: Cut, closer: Char): Boolean {
            release(cut)
            dropUntil = closer
            return true
        }

        private fun openCodeBlock(cut: Cut): Boolean {
            release(cut)
            emitMerged()
            inCodeBlock = true
            return true
        }

        private fun emitAll(): Boolean {
            if (pending.isNotEmpty()) release(Cut(pending.length))
            return false
        }

        /**
         * The only place text leaves the chunker. Every path (a sentence end, a safe break, the hold
         * limit, a code fence, the final flush) releases through here, and [SpeakableText.clean]
         * enforces the invariant that no segment carries raw markup: complete tags and links are
         * reduced, unfinished trailing markup is dropped or reduced to its text.
         */
        private fun release(cut: Cut): Boolean {
            val raw = pending.substring(0, cut.length)
            pending.deleteRange(0, cut.length + cut.skip)
            val spoken = SpeakableText.clean(raw)
            if (spoken.isNotEmpty()) splitLong(spoken).forEach { out += merger.add(VoiceText(it)) }
            return true
        }

        private fun emitMerged() {
            out += merger.take()
        }

        private fun splitLong(text: String): List<String> {
            val parts = mutableListOf<String>()
            var rest = text
            while (rest.length > maxSegmentLength) {
                val cut = softCut.indexIn(rest)
                parts += rest.substring(0, cut).trim(',', ' ')
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) parts += rest
            return parts.filter(String::isNotEmpty)
        }
    }

    /** Defaults. */
    companion object {
        /** The default longest segment, in characters. */
        const val DEFAULT_MAX_SEGMENT_LENGTH: Int = 400
        private const val FENCE = "```"
        private const val HARD_LIMIT_FACTOR = 4
    }
}
