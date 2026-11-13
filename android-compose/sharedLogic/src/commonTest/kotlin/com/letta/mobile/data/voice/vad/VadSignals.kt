package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Synthetic 16 kHz signals for detector tests; deterministic (a fixed-seed generator for noise). */
internal class VadSignals(private val rate: Int = 16_000) {
    private var seed = 12_345L

    /** A white-noise sample within +-[amplitude]. */
    private fun noiseSample(amplitude: Int): Int {
        seed = (seed * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
        return ((seed % (2L * amplitude + 1)) - amplitude).toInt()
    }

    fun noise(seconds: Double, amplitude: Int): ShortArray = ShortArray(count(seconds)) { noiseSample(amplitude).toShort() }

    fun silence(seconds: Double): ShortArray = ShortArray(count(seconds))

    fun tone(seconds: Double, hz: Double, amplitude: Int): ShortArray =
        ShortArray(count(seconds)) { (amplitude * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }

    /**
     * Speech-like: a 150 Hz voice with four harmonics, its loudness rising and falling 4 times a
     * second (syllables) between [peak] and a tenth of it, over a quiet noise bed.
     */
    fun speechLike(seconds: Double, peak: Int): ShortArray = ShortArray(count(seconds)) { index ->
        val time = index.toDouble() / rate
        val voice = (1..4).sumOf { harmonic -> sin(2 * PI * 150 * harmonic * time) / harmonic } / 2.1
        val syllable = 0.55 + 0.45 * sin(2 * PI * 4 * time)
        (peak * syllable * voice + noiseSample(BED)).roundToInt().coerceIn(-32_768, 32_767).toShort()
    }

    /**
     * Room-like noise: white noise through a one-pole low-pass (a fan, traffic, a hum of the
     * building), so it has the low zero-crossing rate of voiced sound. Its RMS is about [amplitude] / 2.
     */
    fun rumble(seconds: Double, amplitude: Int): ShortArray = wandering(seconds, amplitude) { 1.0 }

    /** [rumble] whose loudness wanders slowly by +-6 dB (a passing car, a fan changing speed). */
    fun wanderingRumble(seconds: Double, amplitude: Int): ShortArray =
        wandering(seconds, amplitude) { time -> 10.0.pow(0.3 * sin(2 * PI * 0.4 * time)) }

    /**
     * Keyboard-like bursts every [every] seconds: [click] seconds of loud, low-passed noise decaying
     * to nothing, over a quiet [ROOM] rumble.
     */
    fun clicks(seconds: Double, every: Double, click: Double, amplitude: Int): ShortArray {
        val out = rumble(seconds, ROOM)
        val burst = rumble(click, amplitude)
        var start = 0.0
        while (start + click < seconds) {
            val at = count(start)
            burst.forEachIndexed { index, sample ->
                val decay = 1.0 - index.toDouble() / burst.size
                out[at + index] = (out[at + index] + sample * decay).roundToInt().coerceIn(-32_768, 32_767).toShort()
            }
            start += every
        }
        return out
    }

    private fun wandering(seconds: Double, amplitude: Int, gain: (Double) -> Double): ShortArray {
        var state = 0.0
        return ShortArray(count(seconds)) { index ->
            state += (noiseSample(amplitude * LOW_PASS_GAIN) - state) * LOW_PASS
            (state * gain(index.toDouble() / rate)).roundToInt().coerceIn(-32_768, 32_767).toShort()
        }
    }

    private fun count(seconds: Double): Int = (seconds * rate).roundToInt()

    companion object {
        /** The quiet room the louder signals sit on. */
        const val BED = 100

        /** The rumble of a quiet room. */
        const val ROOM = 150

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
