package com.letta.mobile.data.voice.audio.pcm

import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM only: the output is large (16.8 million samples), too heavy for the browser test runners. */
class StreamingResamplerLargeOutputTest {
    @Test
    fun oneCallReturnsEveryReadySampleEvenPastSixteenMillion() {
        val input = ShortArray(350_000) { (it % 2_000).toShort() }
        val resampler = StreamingResampler(PcmFormat(8_000), PcmFormat(384_000))
        val processed = resampler.process(input)
        val tail = resampler.finish()
        assertTrue(processed.size > 1 shl 24, "process returned only ${processed.size} samples")
        assertEquals(16_800_000, processed.size + tail.size)
    }
}
