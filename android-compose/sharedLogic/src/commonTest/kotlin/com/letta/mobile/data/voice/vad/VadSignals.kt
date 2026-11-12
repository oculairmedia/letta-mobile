package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import kotlin.math.PI
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

    private fun count(seconds: Double): Int = (seconds * rate).roundToInt()

    companion object {
        /** The quiet room the louder signals sit on. */
        const val BED = 100

        val format = AudioFrameFormat()
    }
}

/** Every hop's probability for [signal], in order. */
internal fun SpeechDetector.probabilities(signal: ShortArray): List<Float> =
    (0 until signal.size / hop.samples).map { index ->
        probability(signal.copyOfRange(index * hop.samples, (index + 1) * hop.samples)).value
    }
