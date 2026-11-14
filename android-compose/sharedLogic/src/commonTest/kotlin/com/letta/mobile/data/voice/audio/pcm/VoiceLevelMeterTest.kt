package com.letta.mobile.data.voice.audio.pcm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The voice band's level: silence reads 0, loud speech near 1, and it falls back to exactly 0. */
class VoiceLevelMeterTest {
    private val step = VoiceLevelMeter.INTERVAL_MILLIS

    /** Steps [meter] with [reading] [times] times, one publish interval apart. */
    private fun VoiceLevelMeter.feed(reading: Float, times: Int): Float {
        repeat(times) { update(reading, step) }
        return value
    }

    /** A sine tone [dbBelowFullScale] dB under full scale, as the ear's level of it. */
    private fun levelAt(dbBelowFullScale: Double): Float {
        val amplitude = 32_767.0 * kotlin.math.sqrt(2.0) * kotlin.math.exp(-dbBelowFullScale / 20.0 * kotlin.math.ln(10.0))
        val samples = ShortArray(512) { i -> (amplitude.coerceAtMost(32_767.0) * kotlin.math.sin(i * 0.2)).toInt().toShort() }
        return PcmLevels.level(samples).value
    }

    @Test
    fun digitalSilenceAndAQuietRoomReadZero() {
        val meter = VoiceLevelMeter()
        assertEquals(0f, meter.feed(PcmLevels.level(ShortArray(512)).value, 30))
        assertEquals(0f, meter.feed(levelAt(55.0), 30), "a room at -55 dBFS is silence")
        assertEquals(0f, meter.feed(levelAt(46.0), 30), "and so is one at -46 dBFS")
    }

    @Test
    fun loudSpeechReadsNearOneAndOrdinarySpeechInTheMiddle() {
        val loud = VoiceLevelMeter()
        assertTrue(loud.feed(levelAt(8.0), 30) > 0.95f, "a raised voice close to the microphone is full")
        val ordinary = VoiceLevelMeter()
        val middle = ordinary.feed(levelAt(25.0), 30)
        assertTrue(middle in 0.35f..0.75f, "speech at -25 dBFS reads $middle")
    }

    @Test
    fun itRisesQuicklyAndFallsSlowly() {
        val meter = VoiceLevelMeter()
        meter.update(1f, step)
        val afterOneRise = meter.value
        assertTrue(afterOneRise > 0.4f, "one interval of loud speech already lifts it ($afterOneRise)")
        meter.feed(1f, 30)
        assertEquals(1f, meter.value)
        meter.update(0f, step)
        val afterOneFall = meter.value
        assertTrue(afterOneFall in 0.7f..0.95f, "one interval of silence only starts the fall ($afterOneFall)")
    }

    @Test
    fun silenceDecaysToExactlyZeroAndThenIsSettled() {
        val meter = VoiceLevelMeter()
        meter.feed(1f, 30)
        var steps = 0
        while (meter.value > 0f && steps < 100) {
            meter.update(0f, step)
            steps++
        }
        assertEquals(0f, meter.value)
        assertTrue(steps in 5..40, "the fall takes a fraction of a second, not forever ($steps intervals)")
        assertTrue(meter.settled(0f), "nothing is left to publish")
    }

    @Test
    fun aMutedSourceReadsZeroAtOnce() {
        val meter = VoiceLevelMeter()
        meter.feed(1f, 30)
        assertEquals(0f, meter.update(VoiceLevelMeter.MUTED, step), "half-duplex: the ignored microphone reads 0 at once")
        assertTrue(meter.settled(VoiceLevelMeter.MUTED))
    }

    @Test
    fun aNanReadingIsSilence() {
        assertEquals(0f, VoiceLevelMeter().target(Float.NaN))
    }
}
