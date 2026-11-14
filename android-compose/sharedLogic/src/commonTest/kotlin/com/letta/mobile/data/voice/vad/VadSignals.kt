package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import kotlin.jvm.JvmInline
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** A peak sample value, out of 32_767. */
@JvmInline
internal value class Amplitude(val value: Int)

/** A steady sine: [hz] at [amplitude]. */
internal data class Pitch(val hz: Double, val amplitude: Amplitude)

/** Keyboard-like bursts: one every [every], each [click] long, starting at [amplitude] and decaying. */
internal data class ClickTrain(val every: Duration, val click: Duration, val amplitude: Amplitude)

/** Synthetic signals for detector tests at [format]'s rate; deterministic (a fixed-seed generator for noise). */
internal class VadSignals(format: AudioFrameFormat = AudioFrameFormat()) {
    private val rate = format.sampleRateHz
    private var seed = 12_345L

    /** A white-noise sample within +-[amplitude]. */
    private fun noiseSample(amplitude: Amplitude): Int {
        seed = (seed * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
        return ((seed % (2L * amplitude.value + 1)) - amplitude.value).toInt()
    }

    fun noise(length: Duration, amplitude: Amplitude): ShortArray = ShortArray(count(length)) { noiseSample(amplitude).toShort() }

    fun silence(length: Duration): ShortArray = ShortArray(count(length))

    fun tone(length: Duration, pitch: Pitch): ShortArray = ShortArray(count(length)) {
        (pitch.amplitude.value * sin(2 * PI * pitch.hz * it / rate)).roundToInt().toShort()
    }

    /**
     * Speech-like: a 150 Hz voice with four harmonics, its loudness rising and falling 4 times a
     * second (syllables) between [peak] and a tenth of it, over a quiet noise bed.
     */
    fun speechLike(length: Duration, peak: Amplitude): ShortArray = ShortArray(count(length)) { index ->
        val time = index.toDouble() / rate
        val voice = (1..4).sumOf { harmonic -> sin(2 * PI * 150 * harmonic * time) / harmonic } / 2.1
        val syllable = 0.55 + 0.45 * sin(2 * PI * 4 * time)
        clamp(peak.value * syllable * voice + noiseSample(BED))
    }

    /**
     * Room-like noise: white noise through a one-pole low-pass (a fan, traffic, a hum of the
     * building), so it has the low zero-crossing rate of voiced sound. Its RMS is about [amplitude] / 2.
     */
    fun rumble(length: Duration, amplitude: Amplitude): ShortArray = wandering(length, amplitude) { 1.0 }

    /** [rumble] whose loudness wanders slowly by +-6 dB (a passing car, a fan changing speed). */
    fun wanderingRumble(length: Duration, amplitude: Amplitude): ShortArray =
        wandering(length, amplitude) { time -> 10.0.pow(0.3 * sin(2 * PI * 0.4 * time)) }

    /** [train]'s bursts of loud, low-passed noise, each decaying to nothing, over a quiet [ROOM] rumble. */
    fun clicks(length: Duration, train: ClickTrain): ShortArray {
        val out = rumble(length, ROOM)
        val burst = rumble(train.click, train.amplitude)
        var start = Duration.ZERO
        while (start + train.click < length) {
            addDecaying(out, burst, count(start))
            start += train.every
        }
        return out
    }

    /** Mixes [burst] into [out] at [at], fading it linearly to nothing. */
    private fun addDecaying(out: ShortArray, burst: ShortArray, at: Int) {
        burst.forEachIndexed { index, sample ->
            val decay = 1.0 - index.toDouble() / burst.size
            out[at + index] = clamp(out[at + index] + sample * decay)
        }
    }

    private fun wandering(length: Duration, amplitude: Amplitude, gain: (Double) -> Double): ShortArray {
        var state = 0.0
        return ShortArray(count(length)) { index ->
            state += (noiseSample(Amplitude(amplitude.value * LOW_PASS_GAIN)) - state) * LOW_PASS
            clamp(state * gain(index.toDouble() / rate))
        }
    }

    private fun count(length: Duration): Int = (length.toDouble(DurationUnit.SECONDS) * rate).roundToInt()

    private fun clamp(value: Double): Short = value.roundToInt().coerceIn(-32_768, 32_767).toShort()

    companion object {
        /** The quiet room the louder signals sit on. */
        val BED = Amplitude(100)

        /** The rumble of a quiet room. */
        val ROOM = Amplitude(150)

        /** One-pole low-pass coefficient (about 400 Hz at 16 kHz). */
        private const val LOW_PASS = 0.15

        /** Makes up the low-pass's loss so a rumble's RMS lands near half its amplitude. */
        private const val LOW_PASS_GAIN = 3

        val format = AudioFrameFormat()
    }
}

/** Every hop's probability for [signal], in order. */
internal fun SpeechDetector.probabilities(signal: ShortArray): List<Float> =
    (0 until signal.size / hop.samples).map { index ->
        probability(signal.copyOfRange(index * hop.samples, (index + 1) * hop.samples)).value
    }
