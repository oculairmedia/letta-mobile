package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A streaming mono resampler for playback: windowed-sinc interpolation (Blackman window, 16 zero
 * crossings per side) whose cutoff sits just under the lower of the two Nyquist rates, so
 * downsampling does not alias and upsampling does not image.
 *
 * Output sample n is taken at input time n * from / to, computed in exact integer arithmetic, so
 * the output does not depend on how the input was split into chunks: feeding a clip whole or in
 * any pieces gives identical samples, and chunk boundaries cannot click. [process] holds back the
 * last few input samples it still needs; [finish] flushes them. Not thread-safe; one stream per
 * instance.
 */
class StreamingResampler(
    from: PcmFormat,
    to: PcmFormat,
) {
    private val inRate = from.sampleRateHz.toLong()
    private val outRate = to.sampleRateHz.toLong()
    private val kernel: SincKernel
    private var buffer: FloatArray
    private var length: Int
    private var bufferStart: Long
    private var produced = 0L
    private var received = 0L

    init {
        require(from.channels == 1) { "Resampler input must be mono" }
        require(to.channels == 1) { "Resampler output must be mono" }
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
        kernel = SincKernel(min(1.0, outRate.toDouble() / inRate) * ROLL_OFF)
        buffer = FloatArray(kernel.halfWidth * 2 + INITIAL_CAPACITY)
        length = kernel.halfWidth
        bufferStart = -kernel.halfWidth.toLong()
    }

    /** Resamples [input], returning every output sample that can be computed so far. */
    fun process(input: ShortArray): ShortArray {
        input.forEach { append(it.toFloat()) }
        received += input.size
        return produce(Long.MAX_VALUE)
    }

    /** Flushes the held-back tail: the output then totals ceil(input * to / from) samples. */
    fun finish(): ShortArray {
        repeat(kernel.halfWidth * 2 + 1) { append(0f) }
        val total = (received * outRate + inRate - 1) / inRate
        return produce(total)
    }

    /** Every output sample computable from the buffered input, up to [limit] in total. */
    private fun produce(limit: Long): ShortArray {
        val count = minOf(readyCount(), limit) - produced
        if (count <= 0) return ShortArray(0)
        check(count <= Int.MAX_VALUE) { "Resampler output exceeds one array" }
        val out = ShortArray(count.toInt()) { nextSample() }
        compact()
        return out
    }

    /**
     * How many output samples the buffered input covers: sample n needs input up to
     * floor(n * from / to) + halfWidth, so every n below (end - halfWidth) * to / from is ready.
     */
    private fun readyCount(): Long {
        val covered = bufferStart + length - kernel.halfWidth
        if (covered <= 0) return 0
        return (covered * outRate + inRate - 1) / inRate
    }

    private fun nextSample(): Short {
        val position = produced * inRate
        produced++
        return sampleAt(position / outRate, (position % outRate).toDouble() / outRate)
    }

    private fun sampleAt(whole: Long, fraction: Double): Short {
        var sum = 0.0
        var weights = 0.0
        val base = (whole - bufferStart).toInt()
        for (offset in 1 - kernel.halfWidth..kernel.halfWidth) {
            val weight = kernel.at(offset - fraction)
            sum += buffer[base + offset] * weight
            weights += weight
        }
        val value = if (weights == 0.0) 0.0 else sum / weights
        return value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun compact() {
        val next = produced * inRate / outRate
        val drop = (next - kernel.halfWidth + 1 - bufferStart).toInt()
        if (drop <= 0) return
        buffer.copyInto(buffer, 0, drop, length)
        length -= drop
        bufferStart += drop
    }

    private fun append(sample: Float) {
        if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[length++] = sample
    }

    private companion object {
        const val ROLL_OFF = 0.92
        const val INITIAL_CAPACITY = 4_096
    }
}

/** A Blackman-windowed sinc low-pass sampled finely enough to interpolate linearly. */
private class SincKernel(private val cutoff: Double) {
    val halfWidth: Int = ceil(ZERO_CROSSINGS / cutoff).toInt()
    private val table = DoubleArray(halfWidth * PHASES + 2) { index -> exact(index.toDouble() / PHASES) }

    /** The kernel at [distance] input samples from the output instant. */
    fun at(distance: Double): Double {
        val scaled = abs(distance) * PHASES
        val index = scaled.toInt()
        if (index >= table.size - 1) return 0.0
        val fraction = scaled - index
        return table[index] + (table[index + 1] - table[index]) * fraction
    }

    private fun exact(distance: Double): Double {
        if (distance >= halfWidth) return 0.0
        val x = PI * cutoff * distance
        val sinc = if (distance == 0.0) 1.0 else sin(x) / x
        val window = distance / halfWidth
        val blackman = BLACKMAN_A0 + BLACKMAN_A1 * cos(PI * window) + BLACKMAN_A2 * cos(2 * PI * window)
        return cutoff * sinc * blackman
    }

    private companion object {
        const val ZERO_CROSSINGS = 16.0
        const val PHASES = 256
        const val BLACKMAN_A0 = 0.42
        const val BLACKMAN_A1 = 0.5
        const val BLACKMAN_A2 = 0.08
    }
}
