package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CompletedActivityCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completedNotificationIsCompactUntilItsSummaryRowExpands() {
        setContent(
            UiSubagentNotification(
                status = "completed",
                summary = "Research finished",
                result = "The complete report",
                usage = "123 tokens",
                transcriptUri = "/tmp/report.log",
                taskId = "task-42",
                durationMs = 2_500L,
            ),
        )

        composeRule.onNodeWithText("Research finished").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Completed")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithText("The complete report").assertCountEquals(0)
        composeRule.onAllNodesWithText("123 tokens").assertCountEquals(0)
        composeRule.onAllNodesWithText("task-42").assertCountEquals(0)
        composeRule.onAllNodesWithText("/tmp/report.log", substring = true).assertCountEquals(0)

        composeRule.onNodeWithContentDescription("Show full report").performClick()
        composeRule.onNodeWithText("The complete report").assertIsDisplayed()
        composeRule.onNodeWithText("123 tokens").assertIsDisplayed()
        composeRule.onNodeWithText("task-42").assertIsDisplayed()
        composeRule.onNodeWithText("/tmp/report.log", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("completed-activity-summary").performClick()
        composeRule.onAllNodesWithText("The complete report").assertCountEquals(0)
    }

    @Test
    fun failedNotificationRemainsExpandedWithItsActualStatus() {
        setContent(
            UiSubagentNotification(
                status = "failed",
                summary = "Worker failed",
                result = "Failure evidence",
                usage = null,
                transcriptUri = null,
                taskId = "task-failed",
            ),
        )

        composeRule.onNodeWithText("Task failed").assertIsDisplayed()
        composeRule.onNodeWithText("Worker failed").assertIsDisplayed()
        composeRule.onNodeWithText("Show full report").assertIsDisplayed()
        composeRule.onAllNodesWithText("Completed").assertCountEquals(0)
    }

    @Test
    fun runningNotificationRemainsExpandedWithItsActualStatus() {
        setContent(
            UiSubagentNotification(
                status = "running",
                summary = "Still working",
                result = null,
                usage = null,
                transcriptUri = null,
                taskId = "task-running",
            ),
        )

        composeRule.onNodeWithText("Task running").assertIsDisplayed()
        composeRule.onNodeWithText("Still working").assertIsDisplayed()
        composeRule.onAllNodesWithText("Completed").assertCountEquals(0)
    }

    @Test
    fun cancelledNotificationRemainsExpandedWithItsActualStatus() {
        setContent(
            UiSubagentNotification(
                status = "cancelled",
                summary = "Stopped by user",
                result = null,
                usage = null,
                transcriptUri = null,
                taskId = "task-cancelled",
            ),
        )

        composeRule.onNodeWithText("Task cancelled").assertIsDisplayed()
        composeRule.onNodeWithText("Stopped by user").assertIsDisplayed()
        composeRule.onAllNodesWithText("Completed").assertCountEquals(0)
    }

    @Test
    fun commandNotificationDoesNotOfferWorkerConversation() {
        setContent(
            UiSubagentNotification(
                status = "completed",
                summary = "Command finished",
                result = "command output",
                usage = null,
                transcriptUri = null,
                taskId = "exec_17",
                toolCallId = "tool-command",
                subagentAgentId = "agent-not-a-worker",
            ),
        )

        composeRule.onNodeWithContentDescription("Show full report").performClick()
        composeRule.onAllNodesWithText("View conversation").assertCountEquals(0)
    }

    @Test
    fun explicitWorkerNotificationKeepsConversationActionAfterExpansion() {
        var opened: SubagentTodoSheetTarget? = null
        setContent(
            notification = UiSubagentNotification(
                status = "completed",
                summary = "Worker finished",
                result = "worker report",
                usage = null,
                transcriptUri = null,
                toolCallId = "tool-worker",
                subagentAgentId = "agent-worker",
            ),
            onOpenSubagent = { opened = it },
        )

        composeRule.onNodeWithContentDescription("Show full report").performClick()
        composeRule.onNodeWithText("View conversation").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals("tool-worker", opened?.toolCallId)
            assertEquals("agent-worker", opened?.subagentAgentId)
        }
    }

    private fun setContent(
        notification: UiSubagentNotification,
        onOpenSubagent: (SubagentTodoSheetTarget) -> Unit = {},
    ) {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompositionLocalProvider(LocalSubagentTodoSheetOpener provides onOpenSubagent) {
                        SubagentNotificationCard(notification = notification)
                    }
                }
            }
        }
    }
}
