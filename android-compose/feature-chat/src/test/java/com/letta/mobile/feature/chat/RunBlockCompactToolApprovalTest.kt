package com.letta.mobile.feature.chat

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class RunBlockCompactToolApprovalTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactedRunToolCallsRenderApprovalActionsAndSubmitAllToolIds() {
        var submittedRequestId: String? = null
        var submittedToolCallIds: List<String>? = null
        var submittedApprove: Boolean? = null
        var submittedReason: String? = "not-called"

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    RunBlock(
                        messages = listOf(
                            toolMessage(
                                id = "tc-a",
                                command = "pwd",
                                approvalRequest = approvalRequest(),
                            ),
                            toolMessage(id = "tc-b", command = "ls"),
                        ),
                        collapsed = false,
                        onToggleCollapsed = {},
                        activeApprovalRequestId = null,
                        onApprovalDecision = { requestId, toolCallIds, approve, reason ->
                            submittedRequestId = requestId
                            submittedToolCallIds = toolCallIds
                            submittedApprove = approve
                            submittedReason = reason
                        },
                    ) { message, _, rowModifier ->
                        Text(text = message.id, modifier = rowModifier)
                    }
                }
            }
        }

        composeRule.onNodeWithText("2 tool calls").assertIsDisplayed()
        composeRule.onNodeWithText("Review the requested tool actions before continuing.").assertIsDisplayed()
        composeRule.onNodeWithText("Reject").assertIsDisplayed()
        composeRule.onNodeWithText("Approve").assertIsDisplayed()

        composeRule.onNodeWithText("Approve").performClick()

        composeRule.runOnIdle {
            assertEquals("approval-1", submittedRequestId)
            assertEquals(listOf("call-a", "call-b"), submittedToolCallIds)
            assertTrue(submittedApprove == true)
            assertNull(submittedReason)
        }
    }

    private fun approvalRequest() = UiApprovalRequest(
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

    private fun toolMessage(
        id: String,
        command: String,
        approvalRequest: UiApprovalRequest? = null,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-05-09T00:00:00Z",
        runId = "run-1",
        approvalRequest = approvalRequest,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"$command"}""",
                result = null,
                toolCallId = if (id == "tc-a") "call-a" else "call-b",
            )
        ),
    )
}
