@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/** letta-mobile-kr39h: the compaction divider keeps its summary behind a disclosure. */
class CompactionDividerRowUiTest {
    @Test
    fun theSummaryOpensAndClosesFromTheDivider() = runComposeUiTest {
        setContent { MaterialTheme { CompactionDividerRow(summary = "Set up the repo.") } }
        onNodeWithText("Conversation compacted").assertExists()
        onNodeWithTag(ChatCompactionTestTags.SUMMARY).assertDoesNotExist()

        onNodeWithTag(ChatCompactionTestTags.TOGGLE).performClick()
        onNodeWithTag(ChatCompactionTestTags.SUMMARY).assertExists()

        onNodeWithTag(ChatCompactionTestTags.TOGGLE).performClick()
        onNodeWithTag(ChatCompactionTestTags.SUMMARY).assertDoesNotExist()
    }

    @Test
    fun aBlankSummarySaysNoneWasRecorded() = runComposeUiTest {
        setContent { MaterialTheme { CompactionDividerRow(summary = " ") } }
        onNodeWithTag(ChatCompactionTestTags.TOGGLE).performClick()
        onNodeWithText("No summary recorded").assertExists()
    }
}
