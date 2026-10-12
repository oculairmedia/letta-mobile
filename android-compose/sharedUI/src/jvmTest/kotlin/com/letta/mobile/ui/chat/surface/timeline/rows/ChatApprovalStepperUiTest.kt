@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bzvro.14 (F14, G47): several approvals waiting at once are answered one at a time
 * with a "1 of N" header, submitted together after the last; an answer made offline says it waits.
 */
class ChatApprovalStepperUiTest {
    private fun parked(id: String): UiMessage = message("m-$id", "assistant", "").copy(
        approvalRequest = UiApprovalRequest(
            requestId = "perm-$id",
            toolCalls = listOf(UiApprovalToolCall(toolCallId = id, name = "Bash", arguments = """{"command":"echo $id"}""")),
            details = PendingApprovalDetails(approvalId = "perm-$id", toolCallId = id, toolName = "Bash"),
        ),
    )

    private val three = listOf(parked("a"), parked("b"), parked("c"))

    @androidx.compose.runtime.Composable
    private fun Page(messages: List<UiMessage>, actions: RecordingChatActions, context: ChatRowContext = rowContext()) {
        MaterialTheme {
            CompositionLocalProvider(LocalApprovalStepper provides rememberApprovalStepper(messages, actions)) {
                Column { messages.forEach { RenderRow(single(it), context, rowCallbacks(actions)) } }
            }
        }
    }

    @Test
    fun threeParkedApprovalsAreSteppedOneAtATimeAndSubmittedTogether() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { Page(three, actions) }

        onNodeWithText("Approval 1 of 3").assertExists()
        onAllNodesWithText("Approval requested").fetchSemanticsNodes().let { assertEquals(1, it.size, "one card at a time") }

        onNodeWithText("Approve").performClick()
        onNodeWithText("Approval 2 of 3").assertExists()
        onNodeWithText("Approve").performClick()
        onNodeWithText("Approval 3 of 3").assertExists()
        runOnIdle { assertEquals(emptyList(), actions.approvals, "nothing is sent before the last decision") }
        onNodeWithText("Approve").performClick()

        runOnIdle {
            assertEquals(listOf("perm-a", "perm-b", "perm-c"), actions.approvals.map { it.requestId })
            assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), actions.approvals.map { it.toolCallIds })
        }
    }

    @Test
    fun stepsCanGoBackToRevisitADecision() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { Page(three, actions) }

        onNodeWithText("Approve").performClick()
        onNodeWithTag(ChatRowTestTags.APPROVAL_STEP_BACK).performClick()

        onNodeWithText("Approval 1 of 3").assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_STEP_NEXT).performClick()
        onNodeWithText("Approval 2 of 3").assertExists()
    }

    @Test
    fun aLoneApprovalIsAnsweredAtOnceWithoutAHeader() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { Page(three.take(1), actions) }

        onNodeWithTag(ChatRowTestTags.APPROVAL_STEP_HEADER).assertDoesNotExist()
        onNodeWithText("Approve").performClick()

        runOnIdle { assertEquals(listOf("perm-a"), actions.approvals.map { it.requestId }) }
    }

    @Test
    fun anAnswerQueuedOfflineSaysSoAndTakesNoSecondAnswer() = runComposeUiTest {
        val actions = RecordingChatActions()
        val queued = rowContext(itemState = renderState().copy(queuedApprovalRequestIds = persistentSetOf("perm-a")))
        setContent { Page(three.take(1), actions, queued) }

        onNodeWithTag(ChatRowTestTags.APPROVAL_QUEUED).assertExists()
        onNodeWithText("Sending…").assertIsNotEnabled()
    }
}
