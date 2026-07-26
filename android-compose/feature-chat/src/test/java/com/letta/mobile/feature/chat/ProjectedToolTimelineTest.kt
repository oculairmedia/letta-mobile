package com.letta.mobile.feature.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.LocalUseProjectedToolTimeline
import com.letta.mobile.feature.chat.screen.RunBlock
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
class ProjectedToolTimelineTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun projectedToolTimeline_rendersSingleAndMultipleCallsWhenFlagOn() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompositionLocalProvider(LocalUseProjectedToolTimeline provides true) {
                        RunBlock(
                            messages = listOf(
                                toolMessage(
                                    id = "tc-a",
                                    command = "pwd",
                                ),
                                toolMessage(
                                    id = "tc-b",
                                    command = "ls",
                                ),
                            ),
                            collapsed = false,
                            onToggleCollapsed = {},
                        ) { message, _, rowModifier ->
                            Text(text = message.id, modifier = rowModifier)
                        }
                    }
                }
            }
        }

        // Verify rows render in projected mode
        composeRule.onNodeWithText("Bash(pwd)").assertIsDisplayed()
        composeRule.onNodeWithText("Bash(ls)").assertIsDisplayed()
    }

    @Test
    fun projectedToolTimeline_preservesApprovalsOnHydration() {
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
                    CompositionLocalProvider(LocalUseProjectedToolTimeline provides true) {
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
        }

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

    @Test
    fun projectedToolTimeline_fallsBackForSpecialSubagentCards() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompositionLocalProvider(LocalUseProjectedToolTimeline provides true) {
                        RunBlock(
                            messages = listOf(
                                UiMessage(
                                    id = "msg-subagent",
                                    role = "assistant",
                                    content = "",
                                    timestamp = "2026-05-09T00:00:00Z",
                                    runId = "run-1",
                                    toolCalls = listOf(
                                        UiToolCall(
                                            name = "dispatch_agent",
                                            arguments = """{"prompt":"Search repo"}""",
                                            result = null,
                                            toolCallId = "call-sub",
                                            subagentDispatch = UiSubagentDispatch(
                                                toolCallId = "call-sub",
                                                subagentType = "researcher",
                                                description = "Search codebase for usages",
                                                runInBackground = false,
                                                prompt = "Search repo",
                                            ),
                                        )
                                    ),
                                ),
                                // A second message is required: RunBlock short-circuits a
                                // single-message run straight to renderRow, which never
                                // reaches the grouped tool-call path the fallback lives on.
                                toolMessage(id = "tc-b", command = "ls"),
                            ),
                            collapsed = false,
                            onToggleCollapsed = {},
                        ) { message, _, rowModifier ->
                            Text(text = message.id, modifier = rowModifier)
                        }
                    }
                }
            }
        }

        // Verify dedicated subagent dispatch card fallback is rendered
        composeRule.onNodeWithText("Dispatched: Search codebase for usages").assertIsDisplayed()
    }

    @Test
    fun projectedToolTimeline_restoresLegacyRenderingWhenFlagOff() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompositionLocalProvider(LocalUseProjectedToolTimeline provides false) {
                        RunBlock(
                            messages = listOf(
                                toolMessage(id = "tc-a", command = "pwd"),
                                toolMessage(id = "tc-b", command = "ls"),
                            ),
                            collapsed = false,
                            onToggleCollapsed = {},
                        ) { message, _, rowModifier ->
                            Text(text = message.id, modifier = rowModifier)
                        }
                    }
                }
            }
        }

        // Legacy rendering uses "2 tool calls" header
        composeRule.onNodeWithText("2 tool calls").assertIsDisplayed()
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
