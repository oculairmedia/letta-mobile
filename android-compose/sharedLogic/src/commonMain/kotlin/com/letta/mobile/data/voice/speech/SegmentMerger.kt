package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText

/**
 * Merges the pieces after a part's first into segments of at least the sizing's merge goal
 * ([SegmentSizing.mergeGoal]: its minimum capped by its maximum length, never more than
 * [SegmentSizing.maxSegmentLength]); with no goal every piece is its own segment. A part's first
 * piece is never held: it goes out at once, and merging starts after it.
 *
 * With a [SegmentSizing.tailAbsorbLength] (only while merging is on), a segment that has reached
 * the goal is kept one piece longer, so a piece shorter than that length that follows it (the end
 * of a reply, usually) joins it instead of going out as a tiny segment of its own; the join never
 * takes the segment past the maximum. Without it, a segment goes out the moment it reaches the
 * goal, and a tiny last piece can only follow it alone.
 */
internal class SegmentMerger(private val sizing: SegmentSizing) {
    private val held = Held()
    private var partSpoken = false

    /** How many short pieces were joined to a segment that had already reached the goal. */
    var tailsAbsorbed = 0
        private set

    /** Whether any segment has been released yet. */
    var released = false
        private set

    /** Takes [piece]; returns the segments that are complete now. */
    fun add(piece: VoiceText): List<VoiceText> {
        if (sizing.mergeGoal == null || !partSpoken) return listOf(release(piece))
        if (isTail(piece)) return absorb(piece)
        val out = if (mustRelease(piece)) take() else emptyList()
        held.join(piece)
        return out + if (done()) take() else emptyList()
    }

    /** Takes whatever is held as one segment, if anything is. */
    fun take(): List<VoiceText> = held.drain()?.let { listOf(release(it)) }.orEmpty()

    /** The next piece is a new part's first: released alone, then merging resumes. */
    fun startNewPart() {
        partSpoken = false
    }

    /** Drops whatever is held. */
    fun clear() = held.clear()

    private fun absorb(piece: VoiceText): List<VoiceText> {
        tailsAbsorbed++
        held.join(piece)
        return take()
    }

    private fun release(segment: VoiceText): VoiceText {
        released = true
        partSpoken = true
        return segment
    }

    /** A short [piece] that follows a segment already at its goal and fits beside it. */
    private fun isTail(piece: VoiceText): Boolean =
        sizing.isShortTail(piece) && held.atGoal() && held.fitsWith(piece)

    /** What is held goes out before [piece] is added: it is at its goal already, or [piece] would overflow it. */
    private fun mustRelease(piece: VoiceText): Boolean = !held.isEmpty && (held.atGoal() || !held.fitsWith(piece))

    /** Held text at its goal goes out now, unless it waits for a possible tail. */
    private fun done(): Boolean = held.atGoal() && !awaitsTail()

    /** A segment at its goal waits for the next piece while there is room to join a tail to it. */
    private fun awaitsTail(): Boolean = sizing.tailAbsorbLength != null && held.hasRoomForTail()

    /** The text waiting to be released, joined with single spaces. */
    private inner class Held {
        private val text = StringBuilder()

        val isEmpty: Boolean get() = text.isEmpty()

        fun join(piece: VoiceText) {
            if (text.isNotEmpty()) text.append(SPACE)
            text.append(piece.value)
        }

        fun drain(): VoiceText? = if (text.isEmpty()) null else VoiceText(text.toString()).also { clear() }

        fun clear() = text.clear()

        fun atGoal(): Boolean = sizing.mergeGoal?.let { text.length >= it } ?: false

        fun fitsWith(piece: VoiceText): Boolean = text.length + SPACE_LENGTH + piece.value.length <= sizing.maxSegmentLength

        fun hasRoomForTail(): Boolean = text.length + MIN_JOIN <= sizing.maxSegmentLength
    }

    private companion object {
        const val SPACE = ' '
        const val SPACE_LENGTH = 1

        /** A space and one character: the least a joined piece adds. */
        const val MIN_JOIN = SPACE_LENGTH + 1
    }
}
