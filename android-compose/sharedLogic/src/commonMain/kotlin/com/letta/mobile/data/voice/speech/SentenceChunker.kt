package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText

/**
 * Turns a stream of reply text deltas into speakable segments, so speech can start before the
 * reply is complete.
 *
 * A segment ends at a sentence terminator followed by whitespace (not after a known abbreviation,
 * a single initial or a list number), or at a line break. Fenced code blocks are skipped; markdown,
 * links, URLs and HTML are stripped by [SpeakableText]. A segment never exceeds [maxSegmentLength]:
 * longer text is cut at the last clause break or space. [flush] releases whatever is left.
 *
 * Not thread-safe: one chunker serves one reply.
 */
class SentenceChunker(
    private val maxSegmentLength: Int = DEFAULT_MAX_SEGMENT_LENGTH,
) {
    private val pending = StringBuilder()
    private var inCodeBlock = false
    private var inDroppedTag = false
    private val softCut = SoftCut(maxSegmentLength)

    init {
        require(maxSegmentLength >= MIN_SEGMENT_LENGTH) { "maxSegmentLength too small" }
    }

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

    /** Drops everything buffered. */
    fun reset() {
        pending.clear()
        inCodeBlock = false
        inDroppedTag = false
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
                    inDroppedTag -> skipTag()
                    else -> splitProse()
                }
            } while (progressed)
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

        /** Drops the rest of a tag released past the hold limit until it closes; nothing of it is spoken. */
        private fun skipTag(): Boolean {
            val close = pending.indexOf('>')
            if (close < 0) {
                pending.clear()
                if (mode == ScanMode.FINAL) inDroppedTag = false
                return false
            }
            pending.deleteRange(0, close + 1)
            inDroppedTag = false
            return true
        }

        private fun splitProse(): Boolean {
            val fence = pending.indexOf(FENCE)
            val limit = if (fence < 0) pending.length else fence
            val end = SentenceScan(pending, limit, mode).firstEnd()
            val overflow = overflowCut()
            return when {
                end != null -> take(Cut(end.value))
                fence >= 0 -> openCodeBlock(Cut(fence, FENCE.length))
                overflow != null -> releaseOverflow(overflow)
                mode == ScanMode.FINAL -> emitAll()
                else -> false
            }
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
         * Releases overflowing text. Released whole past the hold limit, an unfinished HTML tag at its
         * end is dropped: the text before it is spoken, the tag never is, and the tag's remainder is
         * skipped until it closes (or the reply ends).
         */
        private fun releaseOverflow(cut: Cut): Boolean {
            val tag = if (cut.length == pending.length) unfinishedTagStart() else null
            if (tag == null) return take(cut)
            take(Cut(tag))
            inDroppedTag = true
            return true
        }

        /** Where an unfinished HTML tag (`<` then a letter or `/`, with no `>` after it) starts, or null. */
        private fun unfinishedTagStart(): Int? {
            val start = pending.lastIndexOf('<')
            if (start < 0 || pending.indexOf('>', start) >= 0) return null
            val next = pending.getOrNull(start + 1) ?: return null
            return if (next.isLetter() || next == '/') start else null
        }

        private fun openCodeBlock(cut: Cut): Boolean {
            take(cut)
            inCodeBlock = true
            return true
        }

        private fun emitAll(): Boolean {
            if (pending.isNotEmpty()) take(Cut(pending.length))
            return false
        }

        private fun take(cut: Cut): Boolean {
            val raw = pending.substring(0, cut.length)
            pending.deleteRange(0, cut.length + cut.skip)
            val spoken = SpeakableText.clean(raw)
            if (spoken.isNotEmpty()) out += splitLong(spoken).map(::VoiceText)
            return true
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
        const val DEFAULT_MAX_SEGMENT_LENGTH: Int = 240
        private const val MIN_SEGMENT_LENGTH = 16
        private const val FENCE = "```"
        private const val HARD_LIMIT_FACTOR = 4
    }
}
