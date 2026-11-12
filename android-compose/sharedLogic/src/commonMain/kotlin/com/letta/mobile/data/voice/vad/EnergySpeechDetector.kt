package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.pcm.PcmLevels
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Tuning of [EnergySpeechDetector]. Levels are dBFS, SNRs are dB above the tracked noise floor,
 * zero-crossing rates are crossings per second (so they hold at any sample rate).
 */
data class EnergyDetectorConfig(
    /** Audio per hop. */
    val hop: Duration = 16.milliseconds,
    /** Hops quieter than this are never speech, whatever the floor. */
    val absoluteFloorDb: Double = -60.0,
    /** The signal-to-noise gate: below this SNR the probability is 0. */
    val snrGateDb: Double = 4.0,
    /** At or above this SNR the energy part of the probability is 1. */
    val snrFullDb: Double = 16.0,
    /** Up to this zero-crossing rate a hop sounds voiced and is not penalised. */
    val voicedZcrPerSecond: Double = 3_000.0,
    /** At this rate (white noise is about half the sample rate) the penalty is full. */
    val noiseZcrPerSecond: Double = 6_000.0,
    /** The probability is multiplied by at least this much for noise-like hops. */
    val noiseKeep: Double = 0.3,
    /**
     * The noise floor is the quietest hop in this much recent audio (minimum statistics): the
     * pauses between syllables keep it at the room's level while someone speaks, and a new steady
     * noise is learned within one window.
     */
    val floorWindow: Duration = 1_500.milliseconds,
)

/**
 * The built-in detector: no model, no dependency. Per hop it measures RMS energy and the
 * zero-crossing rate and tracks an adaptive noise floor: the quietest hop of the last
 * [EnergyDetectorConfig.floorWindow], never below [EnergyDetectorConfig.absoluteFloorDb]. The hop's
 * SNR above that floor maps linearly from 0 at [EnergyDetectorConfig.snrGateDb] to 1 at
 * [EnergyDetectorConfig.snrFullDb], scaled down for noise-like (high zero-crossing) hops.
 *
 * Weaknesses: it hears any loud voiced sound (music, a tone, a TV) as speech; a new steady noise
 * counts as speech for up to one floor window; speech with no pause longer than the window lifts
 * the floor to its own level; whispered or fricative-only speech scores low; speech that starts in
 * the very first hop seeds the floor high until the first pause.
 */
class EnergySpeechDetector(
    format: AudioFrameFormat,
    private val config: EnergyDetectorConfig = EnergyDetectorConfig(),
) : SpeechDetector {
    private val hopSeconds = config.hop.toDouble(DurationUnit.SECONDS)
    private val sampleRate = format.sampleRateHz.toDouble()
    private val recent = DoubleArray((config.floorWindow / config.hop).toInt().coerceAtLeast(1))
    private var recorded = 0

    override val hop: DetectorHop = DetectorHop((sampleRate * hopSeconds).toInt().coerceAtLeast(1))

    override fun probability(samples: ShortArray): SpeechProbability {
        val energy = PcmLevels.dbfs(samples)
        val floor = updateFloor(energy)
        if (energy < config.absoluteFloorDb) return SpeechProbability.None
        val snr = energy - floor
        if (snr < config.snrGateDb) return SpeechProbability.None
        val loudness = ((snr - config.snrGateDb) / (config.snrFullDb - config.snrGateDb)).coerceIn(0.0, 1.0)
        return SpeechProbability.clamped((loudness * voicedness(samples)).toFloat())
    }

    override fun reset() {
        recorded = 0
    }

    /** Records [energy] and returns the floor: the quietest recent hop, including this one. */
    private fun updateFloor(energy: Double): Double {
        recent[recorded % recent.size] = energy
        recorded++
        var floor = energy
        for (index in 0 until minOf(recorded, recent.size)) floor = minOf(floor, recent[index])
        return floor.coerceAtLeast(config.absoluteFloorDb)
    }

    /** 1 for voiced-sounding hops, falling to [EnergyDetectorConfig.noiseKeep] for noise-like ones. */
    private fun voicedness(samples: ShortArray): Double {
        val zcr = crossings(samples) * sampleRate / samples.size
        val span = config.noiseZcrPerSecond - config.voicedZcrPerSecond
        val noisiness = ((zcr - config.voicedZcrPerSecond) / span).coerceIn(0.0, 1.0)
        return 1.0 - noisiness * (1.0 - config.noiseKeep)
    }

    private fun crossings(samples: ShortArray): Int {
        var count = 0
        for (index in 1 until samples.size) {
            if ((samples[index - 1] < 0) != (samples[index] < 0)) count++
        }
        return count
    }

    /** Factories. */
    companion object {
        /** Creates energy detectors with [config]. */
        fun factory(config: EnergyDetectorConfig = EnergyDetectorConfig()): SpeechDetectorFactory =
            SpeechDetectorFactory { format -> EnergySpeechDetector(format, config) }
    }
}
