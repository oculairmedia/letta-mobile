@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownBreakoutTest {
    @Test
    fun noSpareWhileTheColumnFillsTheLane() {
        assertEquals(0, breakoutSpare(laneWidthPx = 676, columnMaxWidthPx = 760, gutterPx = 16))
        assertEquals(0, breakoutSpare(laneWidthPx = 780, columnMaxWidthPx = 760, gutterPx = 16))
        assertEquals(1084, breakoutSpare(laneWidthPx = 1876, columnMaxWidthPx = 760, gutterPx = 16))
    }

    @Test
    fun aBlockThatFitsTheColumnStaysInIt() {
        assertEquals(700, breakoutWidth(parentPx = 700, naturalPx = 480, sparePx = 1000, capPx = 1440))
        assertEquals(700, breakoutWidth(parentPx = 700, naturalPx = 960, sparePx = 0, capPx = 1440))
    }

    @Test
    fun aWideBlockTakesTheSpareUpToTheCap() {
        assertEquals(860, breakoutWidth(parentPx = 760, naturalPx = 960, sparePx = 100, capPx = 1440))
        assertEquals(1440, breakoutWidth(parentPx = 760, naturalPx = 960, sparePx = 1084, capPx = 1440))
    }

    /** Rows streaming into a broken-out table never change its width: the header row settles it. */
    @Test
    fun streamingRowsKeepTheTableWidth() = runDesktopComposeUiTest(width = 1900, height = 900) {
        val lane = MarkdownBreakoutLane().apply { update(laneWidthPx = 1876, columnMaxWidthPx = 760, gutterPx = 16) }
        val text = mutableStateOf(TABLE_HEAD + ROW)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalMarkdownBreakoutLane provides lane) {
                MaterialTheme {
                    Box(Modifier.width(COLUMN.dp)) { SharedMarkdownText(text = text.value, isStreaming = true) }
                }
            }
        }
        // The renderer parses off the UI thread: wait for each parse to land as a table.
        val table = { onAllNodesWithTag(SHARED_MARKDOWN_TABLE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS, condition = table)
        val widths = (0 until ROWS).map {
            text.value += ROW
            waitForIdle()
            waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS, condition = table)
            onNodeWithTag(SHARED_MARKDOWN_TABLE_TAG, useUnmergedTree = true).getBoundsInRoot().let { it.right - it.left }
        }
        assertEquals(listOf(1440.dp), widths.distinct())
    }

    private companion object {
        const val COLUMN = 760
        const val ROWS = 4
        const val PARSE_TIMEOUT_MILLIS = 5_000L
        const val TABLE_HEAD = "| Model | Max Clip | Max Res/FPS | Native Audio | Key Strengths | Pricing |\n" +
            "| --- | --- | --- | --- | --- | --- |\n"
        const val ROW = "| Atlas | 20 s | 4K / 60 | Yes | Physics and long takes | From $1 per clip |\n"
    }
}
