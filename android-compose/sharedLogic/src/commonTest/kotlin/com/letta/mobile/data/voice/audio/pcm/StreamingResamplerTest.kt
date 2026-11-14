package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tone: [hz] at [amplitude] sampled at [rate]. */
private data class Tone(val hz: Double, val rate: Int, val amplitude: Double = 10_000.0) {
    fun at(index: Int): Double = amplitude * sin(2 * PI * hz * index / rate)

    fun samples(count: Int): ShortArray = ShortArray(count) { at(it).roundToInt().toShort() }
}

/** One conversion: rates in and out. */
private data class Conversion(val from: Int, val to: Int) {
    fun resampler() = StreamingResampler(PcmFormat(from), PcmFormat(to))

    fun whole(input: ShortArray): ShortArray = resampler().let { it.process(input) + it.finish() }

    fun chunked(input: ShortArray, sizes: List<Int>): ShortArray {
        val resampler = resampler()
        var output = ShortArray(0)
        var offset = 0
        var index = 0
        while (offset < input.size) {
            val end = minOf(input.size, offset + sizes[index++ % sizes.size])
            output += resampler.process(input.copyOfRange(offset, end))
            offset = end
        }
        return output + resampler.finish()
    }
}

class StreamingResamplerTest {
    private val ragged = listOf(1, 7, 100, 333, 2, 64, 1_000, 3, 512)

    private fun maxError(output: ShortArray, expected: Tone, margin: Int): Double =
        (margin until output.size - margin).maxOf { abs(output[it] - expected.at(it)) }

    private fun rms(samples: ShortArray, margin: Int): Double {
        val middle = (margin until samples.size - margin)
        return sqrt(middle.sumOf { samples[it].toDouble() * samples[it] } / middle.count())
    }

    @Test
    fun upsampledSineStaysASine() {
        val input = Tone(440.0, 24_000).samples(4_800)
        val output = Conversion(24_000, 48_000).whole(input)
        assertEquals(9_600, output.size)
        val error = maxError(output, Tone(440.0, 48_000), margin = 200)
        assertTrue(error < 50.0, "max error $error")
    }

    @Test
    fun downsampledSineStaysASine() {
        val input = Tone(1_000.0, 48_000).samples(9_600)
        val output = Conversion(48_000, 16_000).whole(input)
        assertEquals(3_200, output.size)
        val error = maxError(output, Tone(1_000.0, 16_000), margin = 200)
        assertTrue(error < 50.0, "max error $error")
    }

    @Test
    fun oddRatioSineStaysASine() {
        val input = Tone(300.0, 22_050).samples(4_410)
        val output = Conversion(22_050, 48_000).whole(input)
        assertEquals(9_600, output.size)
        val error = maxError(output, Tone(300.0, 48_000), margin = 300)
        assertTrue(error < 50.0, "max error $error")
    }

    @Test
    fun toneAboveTheNewNyquistDoesNotAlias() {
        val input = Tone(12_000.0, 48_000).samples(9_600)
        val output = Conversion(48_000, 16_000).whole(input)
        val ratio = rms(output, 200) / rms(input, 200)
        assertTrue(ratio < 0.01, "alias ratio $ratio")
    }

    @Test
    fun chunkBoundariesDoNotChangeTheOutput() {
        val input = Tone(523.0, 22_050).samples(10_000)
        listOf(Conversion(22_050, 48_000), Conversion(48_000, 16_000), Conversion(24_000, 44_100)).forEach { conversion ->
            assertContentEquals(conversion.whole(input), conversion.chunked(input, ragged), conversion.toString())
        }
    }

    @Test
    fun outputIsContinuousAcrossChunks() {
        val input = Tone(200.0, 24_000).samples(6_000)
        val output = Conversion(24_000, 48_000).chunked(input, listOf(480))
        val largestStep = (1 until output.size).maxOf { abs(output[it] - output[it - 1]) }
        val toneStep = 10_000 * 2 * PI * 200 / 48_000
        assertTrue(largestStep < toneStep * 1.2, "step $largestStep")
    }

    @Test
    fun silenceStaysSilent() {
        val output = Conversion(16_000, 48_000).whole(ShortArray(1_000))
        assertEquals(3_000, output.size)
        assertTrue(output.all { it.toInt() == 0 })
    }
}
