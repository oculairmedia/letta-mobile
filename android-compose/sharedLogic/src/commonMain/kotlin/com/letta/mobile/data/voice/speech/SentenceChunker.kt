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
    }

    /** The first [length] buffered characters become a segment; [skip] more after them are dropped. */
    private data class Cut(val length: Int, val skip: Int = 0)

    /** One pass over the buffer, collecting every segment that can be decided in [mode]. */
    private inner class Drain(private val mode: ScanMode) {
        private val out = mutableListOf<VoiceText>()

        fun run(): List<VoiceText> {
            do {
                val progressed = if (inCodeBlock) skipCode() else splitProse()
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

        private fun splitProse(): Boolean {
            val fence = pending.indexOf(FENCE)
            val limit = if (fence < 0) pending.length else fence
            val end = SentenceScan(pending, limit, mode).firstEnd()
            val overflow = overflowCut()
            return when {
                end != null -> take(Cut(end.value))
                fence >= 0 -> openCodeBlock(Cut(fence, FENCE.length))
                overflow != null -> take(overflow)
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
