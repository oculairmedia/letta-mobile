package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.SamplePosition

/** One detector-sized run of mono samples and where it ends in the capture. */
internal class Hop(val samples: ShortArray, val end: SamplePosition)

/**
 * Re-blocks frames of any size into hops of [size] samples. A gap in sample positions (frames the
 * source dropped) discards the partial hop, so every hop is contiguous audio.
 */
internal class HopSplitter(private val size: DetectorHop) {
    private val pending = ShortArray(size.samples)
    private var filled = 0
    private var expected: Long? = null

    fun split(frame: AudioFrame): List<Hop> {
        if (expected != frame.start.value) filled = 0
        val hops = mutableListOf<Hop>()
        var position = frame.start.value
        frame.samples.forEach { sample ->
            pending[filled++] = sample
            position++
            if (filled == size.samples) {
                hops += Hop(pending.copyOf(), SamplePosition(position))
                filled = 0
            }
        }
        expected = position
        return hops
    }

    fun wipe() {
        pending.fill(0)
        filled = 0
    }
}

/**
 * Keeps a pre-roll ring of the most recent audio and, while a turn is recorded, the turn itself
 * (pre-roll first). Bounded: a turn holds at most [limit] samples; further audio is ignored (the
 * hard cap ends the turn long before). Memory only; [wipe] zeroes everything.
 */
internal class CaptureRecorder(preRoll: Long, private val limit: Long) {
    private val ring = ShortArray(preRoll.toInt())
    private var ringWritten = 0L
    private var turn = ShortArray(0)
    private var turnLength = 0

    /** Starts a turn with the current pre-roll. */
    fun begin() {
        discard()
        val kept = minOf(ringWritten, ring.size.toLong()).toInt()
        turn = ShortArray(INITIAL_CAPACITY.coerceAtLeast(kept))
        turnLength = 0
        val oldest = ringWritten - kept
        for (index in 0 until kept) append(ring[((oldest + index) % ring.size).toInt()])
    }

    /** Adds [samples] to the turn, if one is being recorded. */
    fun record(samples: ShortArray) {
        samples.forEach { append(it) }
    }

    /** Remembers [samples] as the most recent audio, for the next pre-roll. */
    fun remember(samples: ShortArray) {
        if (ring.isEmpty()) return
        samples.forEach { sample ->
            ring[(ringWritten % ring.size).toInt()] = sample
            ringWritten++
        }
    }

    /** The finished turn; the recorder forgets it (the caller owns and later wipes the copy). */
    fun finish(): ShortArray {
        val result = turn.copyOf(turnLength)
        discard()
        return result
    }

    /** Drops the turn being recorded, zeroing it. */
    fun discard() {
        turn.fill(0)
        turn = ShortArray(0)
        turnLength = 0
    }

    /** Zeroes the turn and the pre-roll. */
    fun wipe() {
        discard()
        ring.fill(0)
        ringWritten = 0
    }

    private fun append(sample: Short) {
        if (turnLength >= limit) return
        if (turnLength == turn.size) grow()
        turn[turnLength++] = sample
    }

    private fun grow() {
        val bigger = turn.copyOf(minOf(limit, maxOf(turn.size * 2L, INITIAL_CAPACITY.toLong())).toInt())
        turn.fill(0)
        turn = bigger
    }

    private companion object {
        const val INITIAL_CAPACITY = 16_000
    }
}
