package com.letta.mobile.data.voice.output

import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Warms the speech path (opens or keeps alive its connection) when the person starts speaking, so
 * the reply's first synthesis request skips the connection set-up. At most once per [interval];
 * a failed warm-up is ignored (the real request reports its own failure). The warm-up a session
 * attaches never carries the person's words.
 */
internal class SpeechWarmer(
    private val timeSource: TimeSource,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    @Volatile
    private var warmUp: (suspend () -> Unit)? = null

    @Volatile
    private var last: TimeMark? = null

    /** Uses [block] to warm the current session's speech path; replaces any earlier one. */
    fun attach(block: suspend () -> Unit) {
        warmUp = block
        last = null
    }

    /** The session ended: nothing to warm. */
    fun detach() {
        warmUp = null
    }

    /** The warm-up to run now, or null when none is attached or one ran within the interval. Claims the slot. */
    fun due(): (suspend () -> Unit)? {
        val block = warmUp ?: return null
        val previous = last
        if (previous != null && previous.elapsedNow() < interval) return null
        last = timeSource.markNow()
        return block
    }

    /** Runs [block], ignoring any failure but cancellation. */
    suspend fun run(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        if (error is CancellationException) throw error
    }

    private companion object {
        val DEFAULT_INTERVAL = 30.seconds
    }
}
