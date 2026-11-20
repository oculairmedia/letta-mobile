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
        assertEquals(0.dp, breakoutSpare(laneWidth = 676.dp, columnMaxWidth = 760.dp, gutter = 16.dp))
        assertEquals(0.dp, breakoutSpare(laneWidth = 780.dp, columnMaxWidth = 760.dp, gutter = 16.dp))
        assertEquals(1084.dp, breakoutSpare(laneWidth = 1876.dp, columnMaxWidth = 760.dp, gutter = 16.dp))
    }

    @Test
    fun aBlockThatFitsTheColumnStaysInIt() {
        assertEquals(700.dp, breakoutWidth(parent = 700.dp, natural = 480.dp, spare = 1000.dp, cap = 1440.dp))
        assertEquals(700.dp, breakoutWidth(parent = 700.dp, natural = 960.dp, spare = 0.dp, cap = 1440.dp))
    }

    @Test
    fun aWideBlockTakesTheSpareUpToTheCap() {
        assertEquals(860.dp, breakoutWidth(parent = 760.dp, natural = 960.dp, spare = 100.dp, cap = 1440.dp))
        assertEquals(1440.dp, breakoutWidth(parent = 760.dp, natural = 960.dp, spare = 1084.dp, cap = 1440.dp))
    }

    /** Rows streaming into a broken-out table never change its width: the header row settles it. */
    @Test
    fun streamingRowsKeepTheTableWidth() = runDesktopComposeUiTest(width = 1900, height = 900) {
        val lane = MarkdownBreakoutLane().apply { update(breakoutSpare(laneWidth = 1876.dp, columnMaxWidth = COLUMN.dp, gutter = 16.dp)) }
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
