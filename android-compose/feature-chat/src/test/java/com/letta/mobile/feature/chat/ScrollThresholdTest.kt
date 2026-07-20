package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.calculateLazyIndexForRenderItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollThresholdTest {

    @Test
    fun `first render item maps after persistent typing slot when not streaming`() {
        val items = listOf(scrollTestSingle("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `first render item maps after typing slot`() {
        val items = listOf(scrollTestSingle("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `target index includes typing slot and previous message rows`() {
        val items = listOf(
            scrollTestSingle("newest", ts = "2026-04-20T12:00:00Z"),
            scrollTestSingle("older", ts = "2026-04-20T11:00:00Z"),
        )

        assertEquals(
            2,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `date separator before target adds lazy item offset after typing slot`() {
        val items = listOf(
            scrollTestSingle("today", ts = "2026-04-20T12:00:00Z"),
            scrollTestSingle("yesterday", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            3,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `multiple date separators before target accumulate offsets`() {
        val items = listOf(
            scrollTestSingle("day3", ts = "2026-04-21T12:00:00Z"),
            scrollTestSingle("day2", ts = "2026-04-20T12:00:00Z"),
            scrollTestSingle("day1", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            5,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 2,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `run block boundary timestamp participates in date separator offsets after typing slot`() {
        val items = listOf(
            scrollTestRunBlock("run1", ts = "2026-04-20T12:00:00Z"),
            scrollTestSingle("older", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            3,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }
}
