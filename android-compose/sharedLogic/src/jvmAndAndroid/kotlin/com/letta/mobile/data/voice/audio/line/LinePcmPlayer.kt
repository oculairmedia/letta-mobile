package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.ClipDecoder
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.PcmLevels
import com.letta.mobile.data.voice.audio.pcm.StreamingResampler
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** How a line player decodes and paces audio. */
internal data class PlayerOptions(
    /** Audio per write; one output level is reported per chunk. */
    val chunk: Duration = 20.milliseconds,
    /** How often the end of playback is checked. */
    val drainPoll: Duration = 10.milliseconds,
    /** Playback that makes no progress for this long has failed (a stuck device). */
    val stallTimeout: Duration = 2.seconds,
    /** Where blocking line calls run. */
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * An [AudioPlayer] on a blocking [PcmOutputDevice]. It decodes the clip ([ClipDecoder]: WAVE only;
 * anything else is refused with a [VoiceAudioException]), mixes it down to mono, resamples
 * when the line opened at another rate, and writes it in short chunks, reporting each chunk's
 * level. It returns once the line has played everything it was given. A line that stops playing
 * queued audio for the stall timeout fails PLAYBACK_FAILED, also while a write or the end-of-stream
 * call is still blocked on it (the line is then aborted and released).
 *
 * Cancelling stops at once and never drains: the cancelling coroutine aborts the line (stop and
 * discard queued audio) whatever the writer was doing (writing, or waiting for queued audio to play
 * out), waits for the writer to exit and releases the line before the cancellation completes.
 * Nothing is written after the abort.
 */
internal class LinePcmPlayer(
    private val device: PcmOutputDevice,
    private val options: PlayerOptions = PlayerOptions(),
) : AudioPlayer {
    override suspend fun play(clip: AudioClip, onLevel: (AudioLevel) -> Unit) {
        val pcm = ClipDecoder.decode(clip).toMono()
        val line = acquire(options.dispatcher) { device.open(PcmFormat(pcm.format.sampleRateHz)) }
        try {
            coroutineScope {
                val progress = WriteProgress()
                val writer = launch(options.dispatcher) { LineWriter(line, options, onLevel, progress).playAll(pcm) }
                val watchdog = launch { watchWrites(line, progress, options) }
                var ended = false
                try {
                    writer.join()
                    ended = !writer.isCancelled
                } finally {
                    // A writer cancelled while waiting for the queue to play out may already have
                    // completed here: the queued audio must still be discarded, not left to drain.
                    if (!ended) line.abort()
                }
                watchdog.cancel()
            }
        } finally {
            withContext(options.dispatcher + NonCancellable) { line.release() }
        }
    }

    private fun PcmAudio.toMono(): PcmAudio {
        val channels = format.channels
        if (channels == 1) return this
        val mono = ShortArray(frames) { frame ->
            var sum = 0
            for (channel in 0 until channels) sum += samples[frame * channels + channel]
            (sum / channels).toShort()
        }
        return PcmAudio(mono, PcmFormat(format.sampleRateHz))
    }
}

/**
 * How far a writer has got: frames the line accepted, whether it is still writing (or ending the
 * stream), and whether it is inside a blocking call into the line right now.
 */
private class WriteProgress {
    val queued = AtomicLong()

    @Volatile
    var writing = true

    @Volatile
    var inCall = false

    /** Runs one blocking line call, marked as in progress until it returns. */
    inline fun <T> blocking(call: () -> T): T {
        inCall = true
        try {
            return call()
        } finally {
            inCall = false
        }
    }
}

/** What the watchdog compares between polls: frames played and frames the line has accepted. */
private data class LineProgress(val played: Long, val queued: Long)

/**
 * Fails with PLAYBACK_FAILED when, during the write phase, neither playback nor the accepted
 * audio moves for the stall timeout while the writer waits on the line: blocked inside `write()` or
 * `endOfStream()` (even the very first write, or after everything earlier has played), or retrying
 * writes the line will not take while accepted audio sits unplayed. Failing cancels the scope, whose
 * caller aborts the line so the blocked call returns, then releases it.
 */
private suspend fun watchWrites(line: PcmOutputLine, progress: WriteProgress, options: PlayerOptions) {
    var last = LineProgress(line.playedFrames(), progress.queued.get())
    var stalled = Duration.ZERO
    while (progress.writing) {
        delay(options.drainPoll)
        val now = LineProgress(line.playedFrames(), progress.queued.get())
        val waiting = progress.inCall || now.queued > now.played
        stalled = if (now == last && waiting) stalled + options.drainPoll else Duration.ZERO
        if (stalled >= options.stallTimeout) throw VoiceAudioException(VoiceErrorCode.PLAYBACK_FAILED)
        last = now
    }
}

/** Writes one clip to one line, then waits for it to be played. */
private class LineWriter(
    private val line: PcmOutputLine,
    private val options: PlayerOptions,
    private val onLevel: (AudioLevel) -> Unit,
    private val progress: WriteProgress,
) {
    private var written = 0L

    suspend fun playAll(pcm: PcmAudio) {
        val resampler = pcm.format.sampleRateHz.takeIf { it != line.format.sampleRateHz }
            ?.let { StreamingResampler(pcm.format, PcmFormat(line.format.sampleRateHz)) }
        val step = (pcm.format.sampleRateHz * options.chunk.toDouble(DurationUnit.SECONDS)).toInt().coerceAtLeast(1)
        for (start in pcm.samples.indices step step) {
            val chunk = pcm.samples.copyOfRange(start, minOf(start + step, pcm.samples.size))
            writeFully(resampler?.process(chunk) ?: chunk)
            onLevel(PcmLevels.level(chunk))
        }
        resampler?.let { writeFully(it.finish()) }
        progress.blocking { line.endOfStream() }
        progress.writing = false
        awaitPlayed()
    }

    private suspend fun writeFully(samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            currentCoroutineContext().ensureActive()
            val remaining = if (offset == 0) samples else samples.copyOfRange(offset, samples.size)
            val count = progress.blocking { line.write(remaining, remaining.size) }
            if (count < 0) throw VoiceAudioException(VoiceErrorCode.PLAYBACK_FAILED)
            if (count == 0) delay(options.drainPoll)
            offset += count
            progress.queued.addAndGet(count.toLong())
        }
        written += samples.size
    }

    /** Waits until the line has played [written] frames; fails when it stops making progress. */
    private suspend fun awaitPlayed() {
        var lastPlayed = line.playedFrames()
        var stalled = Duration.ZERO
        while (lastPlayed < written) {
            delay(options.drainPoll)
            val played = line.playedFrames()
            stalled = if (played == lastPlayed) stalled + options.drainPoll else Duration.ZERO
            if (stalled >= options.stallTimeout) throw VoiceAudioException(VoiceErrorCode.PLAYBACK_FAILED)
            lastPlayed = played
        }
    }
}
