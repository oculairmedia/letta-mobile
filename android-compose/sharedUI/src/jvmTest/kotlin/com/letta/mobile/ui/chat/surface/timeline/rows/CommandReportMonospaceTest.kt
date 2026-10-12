@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.markdown.SharedRichMarkdownRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-qvkmg: a background command's report (shell + stdout) is preformatted output, not
 * prose. It used to go through the markdown seam, where the Android renderer paired the `$`s of
 * `T=$(grep …); A=$(cat …)` into TeX math and ran the stdout columns together into a paragraph.
 */
class CommandReportMonospaceTest {
    @Test
    fun aCommandReportRendersAsAToolOutputBlockAndNeverReachesMarkdown() = runComposeUiTest {
        val painted = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides recorder(painted)) {
                    SubagentNotificationCard(notification = notification(taskId = "exec_42"), openSubagent = null)
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY).performClick()
        waitForIdle()

        onAllNodesWithTag(ChatRowTestTags.TOOL_OUTPUT).assertCountEquals(1)
        onNodeWithText(SHELL_LINE).assertExists()
        onNodeWithText(STDOUT_LINE).assertExists()
        assertEquals(emptyList(), painted, "a command report must not be fed to the markdown renderer")
    }

    @Test
    fun aSubagentReportKeepsTheMarkdownPaint() = runComposeUiTest {
        val painted = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides recorder(painted)) {
                    SubagentNotificationCard(
                        notification = notification(taskId = "task_7", subagentAgentId = "agent-7", result = PROSE),
                        openSubagent = null,
                    )
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY).performClick()
        waitForIdle()

        onAllNodesWithTag(ChatRowTestTags.TOOL_OUTPUT).assertCountEquals(0)
        assertEquals(listOf(PROSE), painted)
    }

    private fun recorder(into: MutableList<String>) = SharedRichMarkdownRenderer { text, _, _, _ -> into += text }

    private fun notification(
        taskId: String,
        subagentAgentId: String? = null,
        result: String = "$SHELL_LINE\n$STDOUT_LINE",
    ) = UiSubagentNotification(
        status = "completed",
        summary = "Background command finished",
        result = result,
        usage = null,
        transcriptUri = null,
        taskId = taskId,
        subagentAgentId = subagentAgentId,
    )

    private companion object {
        const val SHELL_LINE = "T=\$(grep -E '/(test|jvmTest|commonTest)/' /tmp/f.txt); A=\$(cat /tmp/f.txt)"
        const val STDOUT_LINE = "90 days ago  2026-07-14  282782"
        const val PROSE = "The subagent finished the **refactor**."
    }
}
