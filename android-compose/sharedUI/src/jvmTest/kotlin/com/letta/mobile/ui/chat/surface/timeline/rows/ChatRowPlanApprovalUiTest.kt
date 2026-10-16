@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/** letta-mobile-8fq4j: ExitPlanMode's plan card and the plan-file write preview, on the shared row. */
class ChatRowPlanApprovalUiTest {
    @Test
    fun exitPlanModeShowsThePlanAndApprovesWithItsOwnArguments() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(planMessage()), rowContext(), rowCallbacks(actions)) } }

        onNodeWithText(PLAN_TITLE).assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_PLAN).assertExists()
        awaitText("Add the card")
        onNodeWithText("Approval requested").assertDoesNotExist()
        onNodeWithText("Approve").performClick()
        runOnIdle {
            val decision = actions.approvals.single()
            assertEquals("req-plan", decision.requestId)
            assertEquals(listOf("plan-1"), decision.toolCallIds)
            assertTrue(decision.approve)
            val updated = AskUserQuestion.decodeAnswerReason(decision.reason)
            assertEquals(PLAN, updated?.get("plan")?.jsonPrimitive?.content, "the approval closes the call with its plan")
        }
    }

    @Test
    fun rejectingAPlanSendsTheFeedback() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(planMessage()), rowContext(), rowCallbacks(actions)) } }

        onNodeWithText("Reject").performClick()
        onNodeWithText("Reject plan").assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REASON).performTextInput("Add a rollback step")
        onNodeWithTag(ChatRowTestTags.APPROVAL_REJECT_CONFIRM).performClick()
        runOnIdle {
            val decision = actions.approvals.single()
            assertEquals(listOf("plan-1"), decision.toolCallIds)
            assertFalse(decision.approve)
            assertEquals("Add a rollback step", decision.reason)
        }
    }

    @Test
    fun aPlanCardWithoutAnOwnerShowsThePlanWithNoControls() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RenderRow(single(planMessage()), rowContext(capabilities = ChatSurfaceCapabilities(approvals = false)))
            }
        }

        onNodeWithTag(ChatRowTestTags.APPROVAL_PLAN).assertExists()
        onNodeWithText("Approve").assertDoesNotExist()
    }

    @Test
    fun aParkedWriteToAPlansFileShowsThePlanInItsApprovalCard() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(planWriteMessage()), rowContext(), rowCallbacks(actions)) } }

        onNodeWithText("Approval requested").assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_PLAN).assertExists()
        onNodeWithText(".../plans/ship-it.md").assertExists()
        onNodeWithText("Approve").performClick()
        runOnIdle {
            val decision = actions.approvals.single()
            assertTrue(decision.approve)
            assertEquals(null, decision.reason, "a plan write is approved like any write")
        }
    }

    @Test
    fun approvingIsDisabledWhileTheAnswerIsSending() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RenderRow(single(planMessage()), rowContext(itemState = renderState(activeApprovalRequestId = "req-plan")))
            }
        }

        onNodeWithText("Sending…").assertIsNotEnabled()
    }

    private companion object {
        const val PLAN_TITLE = "Ready to code? Here is your plan:"
        const val PLAN = "## Plan\n\n1. Add the card\n2. Ship it"
    }

    private fun planMessage() = message("p-1", "assistant", "").copy(
        approvalRequest = UiApprovalRequest(
            requestId = "req-plan",
            toolCalls = listOf(
                UiApprovalToolCall(
                    toolCallId = "plan-1",
                    name = "ExitPlanMode",
                    arguments = """{"plan":"## Plan\n\n1. Add the card\n2. Ship it"}""",
                ),
            ),
        ),
    )

    private fun planWriteMessage() = message("w-1", "assistant", "").copy(
        approvalRequest = UiApprovalRequest(
            requestId = "req-write",
            toolCalls = listOf(
                UiApprovalToolCall(
                    toolCallId = "write-1",
                    name = "Write",
                    arguments = """{"file_path":"/home/me/.letta/plans/ship-it.md","content":"# Ship it\n\n- step"}""",
                ),
            ),
            details = PendingApprovalDetails(approvalId = "perm-write-1", toolCallId = "write-1", toolName = "Write"),
        ),
    )
}
