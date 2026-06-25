package com.letta.mobile.data.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ChannelDisplayModelsTest {

    @Test
    fun libraryStateSummaryLabelHandlesZeroChannels() {
        val state = ChannelLibraryState(emptyList())
        assertEquals("No channels", state.summaryLabel)
        assertTrue(state.isEmpty)
    }

    @Test
    fun libraryStateSummaryLabelHandlesOneChannel() {
        val state = ChannelLibraryState(
            listOf(
                ChannelDisplayItem("1", "T1", "S1", "D1", emptyList(), ChannelDisplayStatus.Idle)
            )
        )
        assertEquals("1 channel", state.summaryLabel)
        assertFalse(state.isEmpty)
    }

    @Test
    fun libraryStateSummaryLabelHandlesMultipleChannels() {
        val state = ChannelLibraryState(
            listOf(
                ChannelDisplayItem("1", "T1", "S1", "D1", emptyList(), ChannelDisplayStatus.Idle),
                ChannelDisplayItem("2", "T2", "S2", "D2", emptyList(), ChannelDisplayStatus.Idle),
            )
        )
        assertEquals("2 channels", state.summaryLabel)
        assertFalse(state.isEmpty)
    }
}
