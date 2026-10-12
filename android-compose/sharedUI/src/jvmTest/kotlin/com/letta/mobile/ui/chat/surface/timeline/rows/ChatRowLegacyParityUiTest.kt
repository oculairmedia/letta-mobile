@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.1 (hard cut S4): the shared rows draw what the legacy Android rows draw
 * (RowParityScreenshotTest measures the pixels; these hold the structure): the run header on its
 * own line with its counts, the settled run folding from it, a permission approval folded into the
 * tool line's disclosure, and the error bubble labelled only at the head of its group.
 */
class ChatRowLegacyParityUiTest {
    private fun call(id: String, status: String? = "success") =
        UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = if (status == null) null else "ok", status = status, toolCallId = id)

    private fun runOf(vararg messages: UiMessage) = ChatRenderItem.RunBlock(runId = "run-1", messages = messages.map { it to GroupPosition.None })

    private val reasoning = UiMessage(id = "r", role = "assistant", content = "Thinking it through.", timestamp = "2026-10-11T16:00:00Z", runId = "run-1", isReasoning = true)
    private val tools = UiMessage(id = "t", role = "assistant", content = "", timestamp = "2026-10-11T16:00:02Z", runId = "run-1", toolCalls = listOf(call("c1"), call("c2", "error")))
    private val reply = UiMessage(id = "a", role = "assistant", content = "Fixed it.", timestamp = "2026-10-11T16:00:09Z", runId = "run-1")

    @Test
    fun theRunSummaryLineSaysHowManyOfItsToolsFailed() = runComposeUiTest {
        // letta-mobile-zxk2n.37: one line, "Failed · 1 of 2", instead of a title and two counts.
        setContent { MaterialTheme { RenderRow(runOf(reasoning, tools, reply), rowContext(newestMessageId = "a")) } }
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertExists()
        onNodeWithText("Failed · 1 of 2", useUnmergedTree = true).assertExists()
        // Folded by default: the tool line ("2 ran - 1 failed") waits under the summary.
        onNodeWithText("2 ran - 1 failed").assertDoesNotExist()
    }

    @Test
    fun aSettledRunFoldsToItsProseUnderItsSummaryLine() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(runOf(reasoning, tools, reply), rowContext(newestMessageId = "a"), rowCallbacks(actions)) } }
        onNodeWithText("2 ran - 1 failed").assertDoesNotExist()
        onNodeWithText("Fixed it.", substring = true).assertExists()
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).performClick()
        runOnIdle { assertEquals(listOf("run-1"), actions.toggledRuns) }
    }

    @Test
    fun anOpenedRunListsItsStepsUnderItsSummaryLine() = runComposeUiTest {
        // The page's toggle set holds the runs the person opened.
        val opened = rowContext(itemState = renderState(collapsedRunIds = setOf("run-1")), newestMessageId = "a")
        setContent { MaterialTheme { RenderRow(runOf(reasoning, tools, reply), opened) } }
        onNodeWithText("2 ran - 1 failed").assertExists()
        onNodeWithText("Fixed it.", substring = true).assertExists()
    }

    @Test
    fun anOlderRunWithWorkKeepsItsSummaryLine() = runComposeUiTest {
        // The line is the only way to the folded steps, so it shows on older runs too.
        setContent { MaterialTheme { RenderRow(runOf(reasoning, tools, reply), rowContext(newestMessageId = null)) } }
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertExists()
    }

    @Test
    fun anOlderProseOnlyRunHasNoHeader() = runComposeUiTest {
        val narration = reply.copy(id = "n", content = "Looking.")
        setContent { MaterialTheme { RenderRow(runOf(narration, reply), rowContext(newestMessageId = null)) } }
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertDoesNotExist()
    }

    @Test
    /**
     * letta-mobile-sieej (P0): the one place a pending row may differ from legacy. A parked permission
     * approval is NOT folded into the tool line: its card shows under the line without a tap.
     */
    fun aPendingPermissionApprovalShowsUnderTheToolLineWithoutATap() = runComposeUiTest {
        val approval = UiMessage(
            id = "ap",
            role = "assistant",
            content = "",
            timestamp = "2026-10-11T16:04:02Z",
            runId = "run-1",
            toolCalls = listOf(call("tc", status = null)),
            approvalRequest = UiApprovalRequest(
                requestId = "req-1",
                toolCalls = listOf(UiApprovalToolCall(toolCallId = "tc", name = "Bash", arguments = "{}")),
                details = PendingApprovalDetails(approvalId = "req-1", toolCallId = "tc", toolName = "Bash"),
            ),
        )
        setContent {
            MaterialTheme { RenderRow(runOf(approval), rowContext(newestMessageId = "ap", toolDetails = ChatToolDetails.Inline)) }
        }
        onNodeWithText("Approval requested").assertExists()
        onNodeWithText("Approve").assertExists()
        onNodeWithText("Approval needed - 1 command").assertExists()
        // Opening the line's disclosure shows the calls, never a second card.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        waitForIdle()
        onNodeWithText("Approval requested").assertExists()
    }

    @Test
    fun anErrorLabelsItselfOnlyAtTheHeadOfItsGroup() = runComposeUiTest {
        val error = UiMessage(id = "e", role = "assistant", content = "Run failed.", timestamp = "2026-10-11T16:05:00Z", isError = true)
        setContent { MaterialTheme { ChatMessageRow(error, rowContext(), rowCallbacks(), GroupPosition.Last) } }
        onNodeWithText("Error").assertDoesNotExist()
        onNodeWithText("Run failed.", substring = true).assertExists()
    }

    @Test
    fun theHeadOfAGroupKeepsTheErrorLabel() = runComposeUiTest {
        val error = UiMessage(id = "e", role = "assistant", content = "Run failed.", timestamp = "2026-10-11T16:05:00Z", isError = true)
        setContent { MaterialTheme { ChatMessageRow(error, rowContext(), rowCallbacks(), GroupPosition.None) } }
        onNodeWithText("Error").assertExists()
    }
}
