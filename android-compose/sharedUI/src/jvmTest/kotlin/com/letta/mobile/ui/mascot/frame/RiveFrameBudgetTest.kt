package com.letta.mobile.ui.mascot.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiveFrameBudgetTest {
    @Test
    fun aWindowWithinBudgetSaysNothing() {
        val reports = mutableListOf<String>()
        val budget = RiveFrameBudget(reports::add, budgetMs = 10.0, windowFrames = 4)
        repeat(8) { budget.record(5.0) }
        assertEquals(emptyList(), reports)
    }

    @Test
    fun aWindowWithAFrameOverBudgetIsReportedOnce() {
        val reports = mutableListOf<String>()
        val budget = RiveFrameBudget(reports::add, budgetMs = 10.0, windowFrames = 4)
        listOf(5.0, 25.0, 5.0).forEach(budget::record)
        assertEquals(emptyList(), reports, "nothing until the window is full")
        budget.record(5.0)
        val report = reports.single()
        assertTrue("1 of 4 frames over the 10.0 ms budget" in report, report)
        assertTrue("worst 25.0 ms" in report, report)
        assertTrue("mean 10.0 ms" in report, report)
        repeat(4) { budget.record(5.0) }
        assertEquals(1, reports.size, "the next window starts clean")
    }
}
