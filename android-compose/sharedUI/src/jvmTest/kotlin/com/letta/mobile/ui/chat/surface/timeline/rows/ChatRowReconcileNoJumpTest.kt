@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.1 (hard cut S4), the "no jumping" rule for the rows S4 reshaped: a settled row
 * that the owner re-emits unchanged (a reconcile: fresh instances, the same content) keeps its height
 * on the very next frame. Covers the run header and its lifted body, the tool line with a folded
 * approval, the folded run, and the error bubble.
 */
class ChatRowReconcileNoJumpTest {
    private fun call(id: String, status: String? = "success") =
        UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = if (status == null) null else "ok", status = status, toolCallId = id)

    private fun runOf(vararg messages: UiMessage) = ChatRenderItem.RunBlock(runId = "run-1", messages = messages.map { it to GroupPosition.None })

    private val settledRun = runOf(
        UiMessage(id = "r", role = "assistant", content = "Thinking it through.", timestamp = "2026-10-11T16:00:00Z", runId = "run-1", isReasoning = true),
        UiMessage(id = "t", role = "assistant", content = "", timestamp = "2026-10-11T16:00:02Z", runId = "run-1", toolCalls = listOf(call("c1"), call("c2", "error"))),
        UiMessage(id = "a", role = "assistant", content = "Fixed it.", timestamp = "2026-10-11T16:00:09Z", runId = "run-1"),
    )

    private val approvalRun = runOf(
        UiMessage(
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
        ),
    )

    private val errorItem: ChatRenderItem = ChatRenderItem.Single(
        UiMessage(id = "e", role = "assistant", content = "Run failed: rate limit exceeded.", timestamp = "2026-10-11T16:05:00Z", isError = true),
        GroupPosition.None,
    )

    /** The same item as fresh instances: what a reconcile hands the row. */
    private fun ChatRenderItem.reconciled(): ChatRenderItem = when (this) {
        is ChatRenderItem.RunBlock -> copy(messages = messages.map { (message, position) -> message.copy() to position })
        is ChatRenderItem.Single -> copy(message = message.copy())
    }

    @Test
    fun aSettledNewestRunKeepsItsHeightOnReconcile() = runComposeUiTest {
        assertNoJump(settledRun, newestId = "a")
    }

    @Test
    fun anOlderFoldedRunKeepsItsHeightOnReconcile() = runComposeUiTest {
        assertNoJump(settledRun, newestId = null)
    }

    @Test
    fun aToolLineWithAFoldedApprovalKeepsItsHeightOnReconcile() = runComposeUiTest {
        assertNoJump(approvalRun, newestId = "ap")
    }

    @Test
    fun theErrorBubbleKeepsItsHeightOnReconcile() = runComposeUiTest {
        assertNoJump(errorItem, newestId = "e")
    }

    private fun ComposeUiTest.assertNoJump(item: ChatRenderItem, newestId: String?) {
        var shown by mutableStateOf(item)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp).testTag(ROW)) {
                    RenderRow(item = shown, context = rowContext(newestMessageId = newestId, toolDetails = ChatToolDetails.Sheet))
                }
            }
        }
        val before = awaitStableHeight()
        shown = item.reconciled()
        mainClock.advanceTimeByFrame()
        val after = onNodeWithTag(ROW).getUnclippedBoundsInRoot().height.value
        assertEquals(before, after, "the row jumped on reconcile (before=$before, after=$after)")
    }

    private fun ComposeUiTest.awaitStableHeight(): Float {
        var stable = 0
        var last = Float.NaN
        while (stable < STABLE_FRAMES) {
            mainClock.advanceTimeByFrame()
            val height = onNodeWithTag(ROW).getUnclippedBoundsInRoot().height.value
            if (height == last) stable++ else stable = 0
            last = height
        }
        return last
    }

    private companion object {
        const val ROW = "reconcile-row"
        const val STABLE_FRAMES = 3
    }
}
