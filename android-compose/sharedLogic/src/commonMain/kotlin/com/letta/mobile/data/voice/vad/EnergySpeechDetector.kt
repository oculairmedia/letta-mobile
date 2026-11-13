package com.letta.mobile.data.voice.vad

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.pcm.PcmLevels
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/** How readily the ear hears speech: [LOW] needs a louder, clearer voice; [HIGH] hears quieter speech and more noise. */
enum class VadSensitivity {
    /** Needs a clearly louder voice; for noisy rooms. */
    LOW,

    /** The default. */
    NORMAL,

    /** Hears quieter speech; for quiet rooms and distant microphones. */
    HIGH,
}

/**
 * Tuning of [EnergySpeechDetector]. Levels are dBFS, SNRs are dB above the tracked noise floor,
 * zero-crossing rates are crossings per second (so they hold at any sample rate), rates are the
 * time a floor takes to cover about two thirds of a step.
 */
data class EnergyDetectorConfig(
    /** Audio per hop. */
    val hop: Duration = 16.milliseconds,
    /** The noise floor never goes below this. */
    val absoluteFloorDb: Double = -60.0,
    /** Hops quieter than this are never speech, however far above the floor they are. */
    val minSpeechDb: Double = -48.0,
    /** The signal-to-noise gate: below this SNR the probability is 0. */
    val snrGateDb: Double = 6.0,
    /** At or above this SNR the energy part of the probability is 1. */
    val snrFullDb: Double = 18.0,
    /** Up to this zero-crossing rate a hop sounds voiced and is not penalised. */
    val voicedZcrPerSecond: Double = 3_000.0,
    /** At this rate (white noise is about half the sample rate) the penalty is full. */
    val noiseZcrPerSecond: Double = 6_000.0,
    /** The probability is multiplied by at least this much for noise-like hops. */
    val noiseKeep: Double = 0.3,
    /** The floor starts as the typical level of this much audio at the start of a capture. */
    val calibration: Duration = 320.milliseconds,
    /** A hop quieter than the floor pulls it down this fast. */
    val floorFall: Duration = 150.milliseconds,
    /** A hop below the gate (room sound, not speech) lifts the floor this slowly. */
    val floorRise: Duration = 2_000.milliseconds,
    /** A hop above the gate (speech, or a new loud noise) lifts the floor only this slowly. */
    val floorCreep: Duration = 8_000.milliseconds,
    /**
     * Backstop for a new steady noise: the floor is at least the quietest hop of this much recent
     * audio, so a sound with no pause at all is learned within one window. Speech pauses keep it low.
     */
    val steadyWindow: Duration = 2_500.milliseconds,
    /** While the app's own speech plays, a separate floor follows the echo this fast, up and down. */
    val playbackRise: Duration = 500.milliseconds,
) {
    /** Tunings. */
    companion object {
        /** The tuning for [sensitivity]. */
        fun forSensitivity(sensitivity: VadSensitivity): EnergyDetectorConfig = when (sensitivity) {
            VadSensitivity.LOW -> EnergyDetectorConfig(minSpeechDb = -42.0, snrGateDb = 8.0, snrFullDb = 20.0)
            VadSensitivity.NORMAL -> EnergyDetectorConfig()
            VadSensitivity.HIGH -> EnergyDetectorConfig(minSpeechDb = -55.0, snrGateDb = 4.0, snrFullDb = 14.0)
        }
    }
}

/**
 * The built-in detector: no model, no dependency. Per hop it measures RMS energy and the
 * zero-crossing rate against an adaptive noise floor: calibrated from the typical
 * level of the first [EnergyDetectorConfig.calibration] of a capture, then pulled down quickly by
 * quieter hops and lifted slowly by room sound (and very slowly by louder sound), never below
 * [EnergyDetectorConfig.absoluteFloorDb] and never below the quietest hop of the last
 * [EnergyDetectorConfig.steadyWindow]. A hop quieter than [EnergyDetectorConfig.minSpeechDb] is never
 * speech. The hop's SNR maps linearly from 0 at [EnergyDetectorConfig.snrGateDb] to 1 at
 * [EnergyDetectorConfig.snrFullDb], scaled down for noise-like (high zero-crossing) hops.
 *
 * While [setOutputActive] is true the room floor is frozen and the SNR is measured against a
 * separate playback floor that learns the app's own echo (desktop microphones have no echo
 * cancellation): nothing is speech while it calibrates on the first
 * [EnergyDetectorConfig.calibration] of playback, then only a voice clearly louder than the echo.
 *
 * Weaknesses: it hears any loud voiced sound (music, a tone, a TV) as speech until the floor learns
 * it; whispered or fricative-only speech scores low; speech that starts in the very first hops
 * seeds the floor high until the first pause.
 */
class EnergySpeechDetector(
    format: AudioFrameFormat,
    private val config: EnergyDetectorConfig = EnergyDetectorConfig(),
) : SpeechDetector {
    private val hopSeconds = config.hop.toDouble(DurationUnit.SECONDS)
    private val sampleRate = format.sampleRateHz.toDouble()
    private val room = NoiseFloor(config, hopsOf(config.steadyWindow), config.floorRise, config.snrGateDb)
    private var playback: NoiseFloor? = null

    override val hop: DetectorHop = DetectorHop((sampleRate * hopSeconds).toInt().coerceAtLeast(1))

    override val noiseFloorDb: Double? get() = (playback ?: room).level

    override fun probability(samples: ShortArray): SpeechProbability {
        val energy = PcmLevels.dbfs(samples)
        val tracking = playback ?: room
        val floor = tracking.track(energy)
        val calibratingEcho = tracking === playback && tracking.calibrating
        if (energy < config.minSpeechDb || calibratingEcho) return SpeechProbability.None
        val snr = energy - floor
        if (snr < config.snrGateDb) return SpeechProbability.None
        val loudness = ((snr - config.snrGateDb) / (config.snrFullDb - config.snrGateDb)).coerceIn(0.0, 1.0)
        return SpeechProbability.clamped((loudness * voicedness(samples)).toFloat())
    }

    override fun setOutputActive(active: Boolean) {
        if (!active) {
            playback = null
        } else if (playback == null) {
            playback = NoiseFloor(
                config,
                window = 0,
                rise = config.playbackRise,
                riseBelowDb = config.snrFullDb,
                fall = config.playbackRise,
            )
        }
    }

    override fun reset() {
        room.reset()
        playback = null
    }

    private fun hopsOf(duration: Duration): Int = (duration / config.hop).toInt().coerceAtLeast(1)

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

/**
 * One adaptive noise floor in dBFS, tracked hop by hop (see [EnergySpeechDetector]): calibrated
 * from the mean level of the first hops, then pulled down at [fall] by quieter hops, lifted at [rise] by hops
 * less than [riseBelowDb] above it and only crept up by louder ones. With a [window] of hops the
 * floor is also at least the quietest of them (0 disables that backstop).
 */
internal class NoiseFloor(
    private val config: EnergyDetectorConfig,
    window: Int,
    rise: Duration,
    private val riseBelowDb: Double,
    fall: Duration = config.floorFall,
) {
    private val calibrationHops = (config.calibration / config.hop).toInt().coerceAtLeast(1)
    private val fallRate = rateOf(fall)
    private val riseRate = rateOf(rise)
    private val creep = rateOf(config.floorCreep)
    private val recent = DoubleArray(window)
    private var heard = 0
    private var calibrationSum = 0.0
    private var tracked: Double? = null

    /** True while the floor is still being calibrated. */
    val calibrating: Boolean get() = heard <= calibrationHops

    /** The current floor, or null before the first hop. */
    val level: Double? get() = tracked?.let { maxOf(it, steadyMinimum()).coerceAtLeast(config.absoluteFloorDb) }

    /** Learns [energy] and returns the floor to judge it against. */
    fun track(energy: Double): Double {
        val clamped = energy.coerceAtLeast(config.absoluteFloorDb)
        if (recent.isNotEmpty()) recent[heard % recent.size] = clamped
        heard++
        tracked = next(tracked, clamped)
        return checkNotNull(level)
    }

    fun reset() {
        heard = 0
        calibrationSum = 0.0
        tracked = null
    }

    private fun next(current: Double?, energy: Double): Double {
        if (current == null || calibrating) {
            calibrationSum += energy
            return calibrationSum / heard
        }
        val rate = when {
            energy < current -> fallRate
            energy < current + riseBelowDb -> riseRate
            else -> creep
        }
        return current + (energy - current) * rate
    }

    /** The quietest hop of the recent window once it is full; the absolute floor before (or without one). */
    private fun steadyMinimum(): Double {
        if (recent.isEmpty() || heard < recent.size) return config.absoluteFloorDb
        return recent.min()
    }

    private fun rateOf(timeConstant: Duration): Double =
        (config.hop / timeConstant).coerceIn(0.0, 1.0)
}
