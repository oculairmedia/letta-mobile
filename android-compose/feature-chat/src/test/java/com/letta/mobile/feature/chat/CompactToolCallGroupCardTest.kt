package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class CompactToolCallGroupCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupedToolCallRowsCanExpandToFullSingleToolDetails() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompactToolCallGroupCard(
                        toolCalls = listOf(
                            UiToolCall(
                                name = "Bash",
                                arguments = """{"command":"pwd"}""",
                                result = "line one\nline two",
                                status = "success",
                                executionTimeMs = 1_250L,
                                toolCallId = "call-a",
                            ),
                            UiToolCall(
                                name = "Read",
                                arguments = """{"file_path":"/tmp/file.txt"}""",
                                result = null,
                                toolCallId = "call-b",
                            ),
                        ),
                        pendingApprovalToolCallIds = emptySet(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("2 tool calls").assertIsDisplayed()
        composeRule.onAllNodesWithText("Arguments").assertCountEquals(0)

        composeRule.onNodeWithText("Bash - Result: line one", substring = true).performClick()

        composeRule.onNodeWithText("Tool: Bash").assertIsDisplayed()
        composeRule.onNodeWithText("Execution time: 1.3s").assertIsDisplayed()
        composeRule.onNodeWithText("Arguments").assertIsDisplayed()
        composeRule.onNodeWithText("""{"command":"pwd"}""").assertIsDisplayed()
        composeRule.onNodeWithText("Output").assertIsDisplayed()
        composeRule.onNodeWithText("2 lines").assertIsDisplayed()

        composeRule.onNodeWithText("Output").performClick()
        composeRule.onNodeWithText("collapse").assertIsDisplayed()
    }

    @Test
    fun groupedToolCallCardShowsApprovalActionsWhenApprovalIsPending() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompactToolCallGroupCard(
                        toolCalls = listOf(
                            UiToolCall(
                                name = "Bash",
                                arguments = """{"command":"pwd"}""",
                                result = null,
                                toolCallId = "call-a",
                            ),
                            UiToolCall(
                                name = "Bash",
                                arguments = """{"command":"ls"}""",
                                result = null,
                                toolCallId = "call-b",
                            ),
                        ),
                        pendingApprovalToolCallIds = setOf("call-a", "call-b"),
                        approvalRequests = listOf(
                            UiApprovalRequest(
                                requestId = "approval-1",
                                toolCalls = listOf(
                                    UiApprovalToolCall(
                                        toolCallId = "call-a",
                                        name = "Bash",
                                        arguments = """{"command":"pwd"}""",
                                    ),
                                    UiApprovalToolCall(
                                        toolCallId = "call-b",
                                        name = "Bash",
                                        arguments = """{"command":"ls"}""",
                                    ),
                                ),
                            )
                        ),
                        activeApprovalRequestId = null,
                        onApprovalDecision = { _, _, _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithText("2 tool calls").assertIsDisplayed()
        composeRule.onNodeWithText("Review the requested tool actions before continuing.").assertIsDisplayed()
        composeRule.onNodeWithText("Reject").assertIsDisplayed()
        composeRule.onNodeWithText("Approve").assertIsDisplayed()
    }
}
