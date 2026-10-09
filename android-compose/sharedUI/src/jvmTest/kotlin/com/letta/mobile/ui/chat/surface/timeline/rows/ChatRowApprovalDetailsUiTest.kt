@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.ApprovalBinding
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.PermissionSuggestion
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-bzvro.11 / .12: the approval card's always-allow rules, blocked path and diff. */
class ChatRowApprovalDetailsUiTest {
    @Test
    fun aParkedEditApprovalDrawsTheCardWithRulesPathAndDiff() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(editApproval(parked = true))) } }

        onNodeWithText("Approval requested").assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_BLOCKED_PATH).assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_DIFF).assertExists()
        onNodeWithTag(ChatRowTestTags.DIFF_BLOCK).assertExists()
        onNodeWithText("/repo/a.txt · +1 -1").assertExists()
        onNodeWithText("Always allow Edit(/repo/**)").assertExists()
    }

    @Test
    fun choosingARuleApprovesWithItsSuggestionId() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(editApproval(parked = true)), rowContext(), rowCallbacks(actions)) } }

        onNodeWithTag("${ChatRowTestTags.APPROVAL_ALWAYS_ALLOW}-allow-edit-repo").performClick()

        runOnIdle {
            val decision = actions.approvals.single()
            assertEquals("req-edit", decision.requestId)
            assertEquals(listOf("call-edit"), decision.toolCallIds)
            assertTrue(decision.approve)
            assertEquals(listOf("allow-edit-repo"), decision.selectedSuggestionIds)
            // Bound to the details the card drew, so the gateway can refuse it if they changed.
            assertEquals(ApprovalBinding("call-edit", "perm-call-edit"), decision.suggestionBinding)
        }
    }

    @Test
    fun plainApproveStillCarriesNoRuleIds() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(editApproval(parked = true)), rowContext(), rowCallbacks(actions)) } }

        onNodeWithText("Approve").performClick()

        runOnIdle { assertEquals(emptyList(), actions.approvals.single().selectedSuggestionIds) }
    }

    @Test
    fun rulesAreNotOfferedWhenTheOwnerCannotTakeApprovals() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RenderRow(single(editApproval(parked = true)), rowContext(capabilities = ChatSurfaceCapabilities(approvals = false)))
            }
        }

        onNodeWithTag("${ChatRowTestTags.APPROVAL_ALWAYS_ALLOW}-allow-edit-repo").assertDoesNotExist()
    }

    /** PR #1805's rule: an approval the runtime resolves (nothing parked) is still only its tool row. */
    @Test
    fun anEditApprovalTheRuntimeResolvesStaysAPlainToolRow() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(editApproval(parked = false))) } }

        onNodeWithText("Approval requested").assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.APPROVAL_DIFF).assertDoesNotExist()
        onNodeWithText("Always allow Edit(/repo/**)").assertDoesNotExist()
    }

    @Test
    fun aLongDiffIsCollapsedUntilShowAllIsPressed() = runComposeUiTest {
        val lines = (1..120).joinToString("\n") { "+line $it" }
        val diff = "@@ -0,0 +1,120 @@\n$lines"
        setContent { MaterialTheme { RenderRow(single(editApproval(parked = true, diff = diff))) } }

        onNodeWithText("line 120", useUnmergedTree = true).assertDoesNotExist()
        // Off-screen below the collapsed diff, so click through the semantics action.
        onNodeWithTag(ChatRowTestTags.APPROVAL_DIFF_SHOW_ALL).performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText("line 120", useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_DIFF_SHOW_ALL).assertDoesNotExist()
    }

    @Test
    fun anUnpreviewableFileShowsTheServersReason() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RenderRow(single(editApproval(parked = true, diffs = listOf(ApprovalDiffPreview("/repo/big.bin", null, "binary file")))))
            }
        }

        onNodeWithText("binary file").assertExists()
        onNodeWithText("/repo/big.bin").assertExists()
    }

    private fun editApproval(
        parked: Boolean,
        diff: String = "@@ -1,2 +1,2 @@\n keep\n-one\n+two",
        diffs: List<ApprovalDiffPreview> = listOf(ApprovalDiffPreview("/repo/a.txt", diff)),
    ): UiMessage = message("m-edit", "assistant", "").copy(
        approvalRequest = UiApprovalRequest(
            requestId = "req-edit",
            toolCalls = listOf(UiApprovalToolCall(toolCallId = "call-edit", name = "Edit", arguments = """{"file_path":"/repo/a.txt"}""")),
            details = if (parked) {
                PendingApprovalDetails(
                    approvalId = "perm-call-edit",
                    toolCallId = "call-edit",
                    toolName = "Edit",
                    suggestions = listOf(PermissionSuggestion("allow-edit-repo", "Edit(/repo/**)")),
                    blockedPath = "/repo/a.txt",
                    diffs = diffs,
                )
            } else {
                null
            },
        ),
    )
}
