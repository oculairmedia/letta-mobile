@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 *
 * letta-mobile-2a7yv: a `Bash` shell (`bash_N`) is a command too, and its report is sectioned like
 * the tool card: the command, each output tail, stderr in the error inset, one duration, no
 * repeated title, and the transcript path copyable.
 */
class CommandReportMonospaceTest {
    @Test
    fun aCommandReportRendersAsToolOutputBlocksAndNeverReachesMarkdown() = runComposeUiTest {
        val painted = mutableListOf<String>()
        show(notification(taskId = "exec_42"), painted)

        onAllNodesWithTag(ChatRowTestTags.TOOL_OUTPUT).assertCountEquals(2)
        onNodeWithText("$ $SHELL_LINE").assertExists()
        onNodeWithText(STDOUT_LINE).assertExists()
        assertEquals(emptyList(), painted, "a command report must not be fed to the markdown renderer")
    }

    @Test
    fun aBackgroundBashReportIsSectionedLikeAToolCard() = runComposeUiTest {
        val painted = mutableListOf<String>()
        show(
            notification(
                taskId = "bash_54",
                result = "$ python3 - <<'PY'\nimport json\nPY\n\nExit code: 0\n\n" +
                    "[stdout - last 2 of 9 lines]\n{\"id\":1}\n{\"id\":2}\n\n[stderr]\nTraceback (most recent call last):",
                usage = "duration_ms: 11926",
                durationMs = 11_926,
                transcriptUri = "/tmp/letta/bash_54.log",
            ),
            painted,
        )

        // Command, stdout, stderr: each its own monospace block, newlines kept line by line.
        onAllNodesWithTag(ChatRowTestTags.TOOL_OUTPUT).assertCountEquals(3)
        onNodeWithText("$ python3 - <<'PY'").assertExists()
        onNodeWithText("import json").assertExists()
        onNodeWithText("{\"id\":2}").assertExists()
        onNodeWithText("stdout · last 2 of 9 lines").assertExists()
        onNodeWithText("Traceback (most recent call last):").assertExists()
        onNodeWithText("Exit code: 0").assertExists()
        // The title once (the compact row), one humanized duration, no raw usage or task chip.
        onAllNodesWithText(SUMMARY).assertCountEquals(1)
        onAllNodesWithText("duration_ms: 11926").assertCountEquals(0)
        onAllNodesWithText("bash_54").assertCountEquals(0)
        onNodeWithTag(ChatRowTestTags.NOTIFICATION_TRANSCRIPT).assertExists()
        onNodeWithContentDescription("Copy transcript path").assertExists()
        assertEquals(emptyList(), painted)
    }

    @Test
    fun aSubagentReportKeepsTheMarkdownPaint() = runComposeUiTest {
        val painted = mutableListOf<String>()
        show(notification(taskId = "task_7", subagentAgentId = "agent-7", result = PROSE), painted)

        onAllNodesWithTag(ChatRowTestTags.TOOL_OUTPUT).assertCountEquals(0)
        assertEquals(listOf(PROSE), painted)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(notification: UiSubagentNotification, painted: MutableList<String>) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides recorder(painted)) {
                    SubagentNotificationCard(notification = notification, openSubagent = null)
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY).performClick()
        waitForIdle()
    }

    private fun recorder(into: MutableList<String>) = SharedRichMarkdownRenderer { text, _, _, _ -> into += text }

    private fun notification(
        taskId: String,
        subagentAgentId: String? = null,
        result: String = "$ $SHELL_LINE\n\n[stdout]\n$STDOUT_LINE",
        usage: String? = null,
        durationMs: Long? = null,
        transcriptUri: String? = null,
    ) = UiSubagentNotification(
        status = "completed",
        summary = SUMMARY,
        result = result,
        usage = usage,
        transcriptUri = transcriptUri,
        taskId = taskId,
        subagentAgentId = subagentAgentId,
        durationMs = durationMs,
    )

    private companion object {
        const val SUMMARY = "Background command \"Inspect journal rows\" completed"
        const val SHELL_LINE = "T=\$(grep -E '/(test|jvmTest|commonTest)/' /tmp/f.txt); A=\$(cat /tmp/f.txt)"
        const val STDOUT_LINE = "90 days ago  2026-07-14  282782"
        const val PROSE = "The subagent finished the **refactor**."
    }
}
