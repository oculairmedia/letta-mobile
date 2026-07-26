package com.letta.mobile.feature.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test
    fun projectedToolTimeline_autoExpandsAfterBoundedDelay() {
        val group = com.letta.mobile.data.chat.projection.ToolTimelineGroup(
            key = "group-1",
            calls = listOf(
                com.letta.mobile.data.chat.projection.ToolTimelineCall(
                    key = "call:c1",
                    toolCallId = "c1",
                    name = "Bash",
                    arguments = """{"command":"pwd"}""",
                    result = null,
                    state = com.letta.mobile.data.chat.projection.ToolTimelineState.Running,
                    summary = "Bash(pwd)",
                )
            ),
            state = com.letta.mobile.data.chat.projection.ToolTimelineState.Running,
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    com.letta.mobile.feature.chat.screen.ProjectedToolTimelineGroupCard(
                        groups = listOf(group),
                        autoExpandDelayMs = 200L,
                        stagedCollapseDelayMs = 100L,
                    )
                }
            }
        }

        // Before delay expires, live status is not visible in collapsed state
        composeRule.onNodeWithText("Executing Bash...").assertDoesNotExist()

        // Advance virtual time past bounded auto-expand delay (200ms)
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()

        // Auto-expand fires: live status line inside expanded content is now displayed
        composeRule.onNodeWithText("Executing Bash...").assertIsDisplayed()
    }

    @Test
    fun projectedToolTimeline_explicitUserExpansion_winsOverAutoCollapse() {
        var groupState by androidx.compose.runtime.mutableStateOf(
            com.letta.mobile.data.chat.projection.ToolTimelineGroup(
                key = "group-1",
                calls = listOf(
                    com.letta.mobile.data.chat.projection.ToolTimelineCall(
                        key = "call:c1",
                        toolCallId = "c1",
                        name = "Bash",
                        arguments = """{"command":"pwd"}""",
                        result = null,
                        state = com.letta.mobile.data.chat.projection.ToolTimelineState.Running,
                        summary = "Bash(pwd)",
                    )
                ),
                state = com.letta.mobile.data.chat.projection.ToolTimelineState.Running,
            )
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    com.letta.mobile.feature.chat.screen.ProjectedToolTimelineGroupCard(
                        groups = listOf(groupState),
                        autoExpandDelayMs = 1000L,
                        stagedCollapseDelayMs = 200L,
                    )
                }
            }
        }

        // User explicitly clicks row to expand before auto-expand delay
        composeRule.onNodeWithText("Bash(pwd)").performClick()
        composeRule.waitForIdle()

        // Verify content expanded by user
        composeRule.onNodeWithText("Executing Bash...").assertIsDisplayed()

        // Complete the call
        groupState = com.letta.mobile.data.chat.projection.ToolTimelineGroup(
            key = "group-1",
            calls = listOf(
                com.letta.mobile.data.chat.projection.ToolTimelineCall(
                    key = "call:c1",
                    toolCallId = "c1",
                    name = "Bash",
                    arguments = """{"command":"pwd"}""",
                    result = "/home/user",
                    state = com.letta.mobile.data.chat.projection.ToolTimelineState.Succeeded,
                    summary = "Bash(pwd)",
                    executionTimeMs = 150L,
                )
            ),
            state = com.letta.mobile.data.chat.projection.ToolTimelineState.Succeeded,
        )

        // Advance past staged collapse delay
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()

        // Explicit user expansion MUST WIN over auto-collapse: content stays displayed!
        composeRule.onNodeWithText("/home/user").assertIsDisplayed()
    }

    @Test
    fun projectedToolTimeline_autoExpandedRow_showsSummaryFirstThenCollapsesOnCompletion() {
        var groupState by androidx.compose.runtime.mutableStateOf(
            com.letta.mobile.data.chat.projection.ToolTimelineGroup(
                key = "group-1",
                calls = listOf(
                    com.letta.mobile.data.chat.projection.ToolTimelineCall(
                        key = "call:c1",
                        toolCallId = "c1",
                        name = "Bash",
                        arguments = """{"command":"pwd"}""",
                        result = null,
                        state = com.letta.mobile.data.chat.projection.ToolTimelineState.Running,
                        summary = "Bash(pwd)",
                    )
                ),
                state = com.letta.mobile.data.chat.projection.ToolTimelineState.Running,
            )
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    com.letta.mobile.feature.chat.screen.ProjectedToolTimelineGroupCard(
                        groups = listOf(groupState),
                        autoExpandDelayMs = 100L,
                        stagedCollapseDelayMs = 300L,
                    )
                }
            }
        }

        // Advance clock so row auto-expands
        composeRule.mainClock.advanceTimeBy(150L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Executing Bash...").assertIsDisplayed()

        // Complete the tool call
        groupState = com.letta.mobile.data.chat.projection.ToolTimelineGroup(
            key = "group-1",
            calls = listOf(
                com.letta.mobile.data.chat.projection.ToolTimelineCall(
                    key = "call:c1",
                    toolCallId = "c1",
                    name = "Bash",
                    arguments = """{"command":"pwd"}""",
                    result = "/root",
                    state = com.letta.mobile.data.chat.projection.ToolTimelineState.Succeeded,
                    summary = "Bash(pwd)",
                    executionTimeMs = 120L,
                )
            ),
            state = com.letta.mobile.data.chat.projection.ToolTimelineState.Succeeded,
        )

        // Frame 1 after completion: static summary ("120ms") is visible immediately
        composeRule.onNodeWithText("120ms").assertIsDisplayed()

        // Advance past staged collapse delay (300ms)
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()

        // Auto-expanded row collapsed after staged delay: detail output is no longer displayed
        composeRule.onNodeWithText("/root").assertDoesNotExist()
    }

    // Regression: an expanded row rendered the cleaned summary AND the raw arguments
    // envelope underneath it, so every tool call showed a duplicate row of raw JSON.
    // The monospace fallback must be suppressed whenever a structured summary exists.
    @Test
    fun expandedToolRow_doesNotRepeatRawArgumentsBesideTheStructuredSummary() {
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

        // Expand the first call's row.
        composeRule.onNodeWithText("Bash(pwd)").performClick()
        composeRule.waitForIdle()

        // The structured summary is what carries the command; the raw JSON envelope
        // must not be painted as a second block.
        composeRule.onNodeWithText("""{"command":"pwd"}""").assertDoesNotExist()
    }

    @Test
    fun togglingTimelineMode_preservesCanonicalDataAndKeys() {
        val messageA = toolMessage(id = "tc-a", command = "pwd")
        val messageB = toolMessage(id = "tc-b", command = "ls")
        val messages = listOf(messageA, messageB)

        val stepKeysBefore = com.letta.mobile.feature.chat.screen
            .compactRunToolCallSteps(messages).map { it.key }

        // The mode is toggled inside ONE composition. Calling setContent twice throws
        // ("has already set content") and would not exercise a live switch anyway.
        var useProjected by mutableStateOf(false)

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompositionLocalProvider(LocalUseProjectedToolTimeline provides useProjected) {
                        RunBlock(
                            messages = messages,
                            collapsed = false,
                            onToggleCollapsed = {},
                        ) { message, _, rowModifier ->
                            Text(text = message.id, modifier = rowModifier)
                        }
                    }
                }
            }
        }

        // Legacy presentation renders the compact group card.
        composeRule.onNodeWithText("2 tool calls").assertIsDisplayed()

        composeRule.runOnIdle { useProjected = true }
        composeRule.waitForIdle()

        // Projected presentation renders per-call rows from the same messages.
        composeRule.onNodeWithText("Bash(pwd)").assertIsDisplayed()
        composeRule.onNodeWithText("Bash(ls)").assertIsDisplayed()

        // The canonical step keys are unchanged by the switch — no migration, no rewrite.
        val stepKeysAfter = com.letta.mobile.feature.chat.screen
            .compactRunToolCallSteps(messages).map { it.key }
        assertEquals(stepKeysBefore, stepKeysAfter)
        assertEquals(listOf(messageA, messageB), messages)
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
