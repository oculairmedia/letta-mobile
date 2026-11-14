package com.letta.mobile.data.voice.audio.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputBufferBytesTest {
    @Test
    fun theBufferHoldsWholeMonoFramesAtEveryCommonRate() {
        listOf(8_000, 11_025, 16_000, 22_050, 24_000, 44_100, 48_000).forEach { rate ->
            val bytes = outputBufferBytes(rate)
            assertEquals(0, bytes % 2, "rate $rate")
            assertEquals(rate / 8, bytes / 2, "rate $rate")
        }
    }
}
