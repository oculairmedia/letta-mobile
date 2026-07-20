package com.letta.mobile.feature.chat

import org.junit.Test

class ScrollThresholdTest {

    @Test
    fun `lazy index accounts for typing slot and date separators across all cases`() {
        assertAllLazyIndexExpectations(
            ScrollTestLazyIndexExpectation(
                expected = 1,
                targetRenderIndex = 0,
                items = listOf(scrollTestSingle("m1", ts = "2026-04-20T12:00:00Z")),
            ),
            ScrollTestLazyIndexExpectation(
                expected = 2,
                targetRenderIndex = 1,
                items = listOf(
                    scrollTestSingle("newest", ts = "2026-04-20T12:00:00Z"),
                    scrollTestSingle("older", ts = "2026-04-20T11:00:00Z"),
                ),
            ),
            ScrollTestLazyIndexExpectation(
                expected = 3,
                targetRenderIndex = 1,
                items = listOf(
                    scrollTestSingle("today", ts = "2026-04-20T12:00:00Z"),
                    scrollTestSingle("yesterday", ts = "2026-04-19T12:00:00Z"),
                ),
            ),
            ScrollTestLazyIndexExpectation(
                expected = 5,
                targetRenderIndex = 2,
                items = listOf(
                    scrollTestSingle("day3", ts = "2026-04-21T12:00:00Z"),
                    scrollTestSingle("day2", ts = "2026-04-20T12:00:00Z"),
                    scrollTestSingle("day1", ts = "2026-04-19T12:00:00Z"),
                ),
            ),
        )
    }

    @Test
    fun `run block boundary timestamp participates in date separator offsets after typing slot`() {
        assertAllLazyIndexExpectations(
            ScrollTestLazyIndexExpectation(
                expected = 3,
                targetRenderIndex = 1,
                items = listOf(
                    scrollTestRunBlock("run1", ts = "2026-04-20T12:00:00Z"),
                    scrollTestSingle("older", ts = "2026-04-19T12:00:00Z"),
                ),
            ),
        )
    }
}
