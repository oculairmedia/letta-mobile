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

    init {
        require(maxSegmentLength >= MIN_SEGMENT_LENGTH) { "maxSegmentLength too small" }
    }

    /** Appends [delta] and returns every segment that is now complete. */
    fun push(delta: VoiceText): List<VoiceText> {
        pending.append(delta.value)
        return drain(final = false)
    }

    /** Returns every remaining segment, including an unfinished sentence, and resets. */
    fun flush(): List<VoiceText> {
        val segments = drain(final = true)
        reset()
        return segments
    }

    /** Drops everything buffered. */
    fun reset() {
        pending.clear()
        inCodeBlock = false
    }

    private fun drain(final: Boolean): List<VoiceText> {
        val out = mutableListOf<VoiceText>()
        while (step(final, out)) Unit
        return out
    }

    /** One unit of progress; false when nothing more can be decided now. */
    private fun step(final: Boolean, out: MutableList<VoiceText>): Boolean =
        if (inCodeBlock) skipCode(final) else splitProse(final, out)

    private fun skipCode(final: Boolean): Boolean {
        val close = pending.indexOf(FENCE)
        if (close < 0) {
            keepTail(if (final) 0 else FENCE.length - 1)
            if (final) inCodeBlock = false
            return false
        }
        pending.deleteRange(0, close + FENCE.length)
        inCodeBlock = false
        return true
    }

    private fun keepTail(count: Int) {
        if (pending.length > count) pending.deleteRange(0, pending.length - count)
    }

    private fun splitProse(final: Boolean, out: MutableList<VoiceText>): Boolean {
        val fence = pending.indexOf(FENCE)
        val limit = if (fence < 0) pending.length else fence
        val end = SentenceBoundaries.find(pending, limit, final)
        return when {
            end > 0 -> take(end, 0, out)
            fence >= 0 -> openCodeBlock(fence, out)
            pending.length > maxSegmentLength -> take(softCut(pending, maxSegmentLength), 0, out)
            final -> emitAll(out)
            else -> false
        }
    }

    private fun openCodeBlock(fence: Int, out: MutableList<VoiceText>): Boolean {
        take(fence, FENCE.length, out)
        inCodeBlock = true
        return true
    }

    private fun emitAll(out: MutableList<VoiceText>): Boolean {
        if (pending.isEmpty()) return false
        take(pending.length, 0, out)
        return false
    }

    /** Emits the first [length] characters as a segment and also drops [skip] more after them. */
    private fun take(length: Int, skip: Int, out: MutableList<VoiceText>): Boolean {
        val raw = pending.substring(0, length)
        pending.deleteRange(0, length + skip)
        val spoken = SpeakableText.clean(raw)
        if (spoken.isNotEmpty()) out += splitLong(spoken).map(::VoiceText)
        return true
    }

    private fun splitLong(text: String): List<String> {
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.length > maxSegmentLength) {
            val cut = softCut(rest, maxSegmentLength)
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /** Defaults. */
    companion object {
        /** The default longest segment, in characters. */
        const val DEFAULT_MAX_SEGMENT_LENGTH: Int = 240
        private const val MIN_SEGMENT_LENGTH = 16
        private const val FENCE = "```"
    }
}
