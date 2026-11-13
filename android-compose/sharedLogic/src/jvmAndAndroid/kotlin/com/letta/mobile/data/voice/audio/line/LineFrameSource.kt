package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.audio.device.MeteredFrameSource
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How the capture thread buffers and when it gives up on a silent line. */
internal data class PumpOptions(
    /** Frames buffered between the capture thread and the collector; the oldest are dropped beyond it. */
    val capacity: Int = 32,
    /** A line that returns nothing for this long is treated as gone (hot-unplug). */
    val stallTimeout: Duration = 2.seconds,
    /** The pause between empty reads. */
    val pollInterval: Duration = 5.milliseconds,
    /** The longest wait for the capture thread to exit after the line was stopped. */
    val joinTimeout: Duration = 2.seconds,
)

/**
 * A frame source on a blocking [PcmInputDevice]. Each collection opens the line, starts a dedicated
 * daemon thread that blocks on it, and hands fixed-size frames to the collector through a bounded
 * buffer that drops the oldest frame when the collector falls behind ([droppedFrames] counts them).
 * Positions start at 0 and advance by exactly one frame per frame read, so a dropped frame leaves a
 * gap but positions never repeat or go backwards.
 *
 * Cancelling the collection stops the line, joins the thread and releases the line before the
 * collection completes. A line failure, or a line that stays silent past the stall timeout, ends
 * the collection with a [VoiceAudioException].
 */
internal class LineFrameSource(
    private val device: PcmInputDevice,
    override val format: AudioFrameFormat,
    private val options: PumpOptions = PumpOptions(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MeteredFrameSource {
    private val dropped = AtomicLong()

    override val droppedFrames: Long get() = dropped.get()

    override fun frames(): Flow<AudioFrame> = flow {
        val line = withContext(dispatcher + NonCancellable) { device.open(format) }
        val pump = CapturePump(line, format, options, dropped)
        try {
            currentCoroutineContext().ensureActive()
            pump.start()
            for (frame in pump.frames) emit(frame)
        } finally {
            withContext(dispatcher + NonCancellable) { pump.stopAndRelease() }
        }
    }
}

/** The capture thread of one collection. */
internal class CapturePump(
    private val line: PcmInputLine,
    private val format: AudioFrameFormat,
    private val options: PumpOptions,
    private val dropped: AtomicLong,
) {
    val frames = Channel<AudioFrame>(options.capacity)

    @Volatile
    private var running = true
    private var next = 0L
    private var idleReads = 0L // touched by the capture thread only
    private val thread = Thread(::pump, "voice-capture").apply { isDaemon = true }

    fun start() = thread.start()

    /** Stops the line, waits for the thread and frees the line. */
    fun stopAndRelease() {
        running = false
        line.stop()
        if (thread.isAlive) thread.join(options.joinTimeout.inWholeMilliseconds)
        line.release()
        frames.close()
    }

    private fun pump() {
        val outcome = runCatching {
            while (running) readFrame()?.let { offer(it) }
        }
        val failure = outcome.exceptionOrNull()?.takeIf { running }
        frames.close(failure?.let { it as? VoiceAudioException ?: VoiceAudioException(VoiceErrorCode.ENGINE_FAILED) })
    }

    /** One full frame, or null when stopped part-way. */
    private fun readFrame(): AudioFrame? {
        val samples = ShortArray(format.samplesPerFrame)
        var filled = 0
        idleReads = 0
        while (filled < samples.size) {
            if (!running) return null
            filled += readInto(samples, filled)
        }
        val frame = AudioFrame(samples, SamplePosition(next))
        next += format.frameSamples
        return frame
    }

    /** Reads what the line has into [samples] after [filled]; returns the count (0 after an idle wait). */
    private fun readInto(samples: ShortArray, filled: Int): Int {
        val buffer = if (filled == 0) samples else ShortArray(samples.size - filled)
        val read = line.read(buffer)
        if (read < 0) throw VoiceAudioException(VoiceErrorCode.ENGINE_FAILED)
        if (read == 0) return 0.also { waitIdle() }
        idleReads = 0
        if (buffer !== samples) buffer.copyInto(samples, filled, 0, read)
        return read
    }

    /** Sleeps one poll interval; a line idle for longer than the stall timeout is UNAVAILABLE. */
    private fun waitIdle() {
        idleReads++
        if (idleReads > options.stallTimeout / options.pollInterval) throw VoiceAudioException(VoiceErrorCode.UNAVAILABLE)
        TimeUnit.MILLISECONDS.sleep(options.pollInterval.inWholeMilliseconds)
    }

    /** Queues [frame], dropping the oldest queued frame while the buffer is full. */
    private fun offer(frame: AudioFrame) {
        while (!frames.trySend(frame).isSuccess) {
            if (frames.isClosedForSend) return
            if (frames.tryReceive().isSuccess) dropped.incrementAndGet()
        }
    }
}
