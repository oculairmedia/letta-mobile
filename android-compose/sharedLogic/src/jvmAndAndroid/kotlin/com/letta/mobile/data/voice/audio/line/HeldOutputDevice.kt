package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.audio.PcmFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps one speaker line open across consecutive streams, so the segments of one reply play on the
 * same line without a close and reopen (and the device's start-up latency) between them.
 *
 * Each [open] hands out a lease on a line. A lease released after playing out everything it
 * queued parks its line; the next [open] in the same format takes it back (its played-frame count
 * starts from zero again for the new stream), and a parked line nobody takes within [idle] is
 * released, as is one still parked when [owner] (the session that holds the speaker) ends. A line
 * that was aborted (the reply was interrupted or stopped), or released with
 * queued audio still unplayed (it failed), is released at once and never reused. One lease at a
 * time: a player plays one stream after the other.
 */
internal class HeldOutputDevice(
    private val device: PcmOutputDevice,
    private val owner: CoroutineScope,
    private val idle: Duration = DEFAULT_IDLE,
) : PcmOutputDevice {
    private val lock = Any()
    private var parked: Parked? = null

    /** A line waiting for the next stream, and the timer that releases it. */
    private class Parked(val line: PcmOutputLine, val asked: PcmFormat, val closer: Job)

    override fun open(format: PcmFormat): PcmOutputLine {
        val waiting = synchronized(lock) { parked.also { parked = null } }
        waiting?.closer?.cancel()
        if (waiting != null && waiting.asked == format) return Lease(waiting.line, format)
        waiting?.line?.release()
        return Lease(device.open(format), format)
    }

    /**
     * Parks [line] and starts its idle timer. The timer's completion releases the line if it is still
     * the one parked under that timer: when the idle passes, when [owner] ends (also before the timer
     * ever ran), but not when [open] took the line back or a newer line replaced it.
     */
    private fun park(line: PcmOutputLine, asked: PcmFormat) {
        val closer = owner.launch(start = CoroutineStart.LAZY) { delay(idle) }
        val previous = synchronized(lock) { parked.also { parked = Parked(line, asked, closer) } }
        previous?.let {
            it.closer.cancel()
            it.line.release()
        }
        closer.invokeOnCompletion { expire(closer) }
        closer.start()
    }

    private fun expire(closer: Job) {
        val expired = synchronized(lock) { parked?.takeIf { it.closer === closer }?.also { parked = null } }
        expired?.line?.release()
    }

    /** One stream's use of a line; its played frames count from the start of the lease. */
    private inner class Lease(private val line: PcmOutputLine, private val asked: PcmFormat) : PcmOutputLine {
        private val baseline = line.playedFrames()
        private var queued = 0L

        @Volatile
        private var aborted = false
        private var released = false

        override val format: PcmFormat get() = line.format

        override fun write(samples: ShortArray, count: Int): Int = line.write(samples, count).also { if (it > 0) queued += it }

        override fun playedFrames(): Long = line.playedFrames() - baseline

        override fun endOfStream() = line.endOfStream()

        override fun abort() {
            aborted = true
            line.abort()
        }

        override fun release() {
            if (released) return
            released = true
            if (aborted || playedFrames() < queued) line.release() else park(line, asked)
        }
    }

    private companion object {
        val DEFAULT_IDLE = 2.seconds
    }
}
