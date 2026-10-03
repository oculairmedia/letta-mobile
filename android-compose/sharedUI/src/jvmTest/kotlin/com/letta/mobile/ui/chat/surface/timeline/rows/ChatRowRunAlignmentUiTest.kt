@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: a settled run's header ("Worked - 2 tools") and its tool summary ("Ran 2
 * commands") start at the timeline's gutter, flush with the agent's prose, as the Android rows do;
 * only their chevrons sit at the end.
 */
class ChatRowRunAlignmentUiTest {
    private fun call(id: String) = UiToolCall(name = "shell", arguments = "ls", result = "ok", status = "success", toolCallId = id)

    private val run = ChatRenderItem.RunBlock(
        runId = "run-1",
        messages = listOf(
            UiMessage(id = "a", role = "assistant", content = "", timestamp = "2026-07-19T12:00:00Z", runId = "run-1", toolCalls = listOf(call("c1"), call("c2"))),
            UiMessage(id = "b", role = "assistant", content = "All done here.", timestamp = "2026-07-19T12:00:09Z", runId = "run-1"),
        ).map { it to GroupPosition.None },
    )

    @Test
    fun theRunHeaderAndToolSummaryAreFlushWithTheProse() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(run, rowContext(newestMessageId = "b", toolDetails = ChatToolDetails.Sheet))
                }
            }
        }
        val prose = onNodeWithTag(ChatRowTestTags.AGENT_TEXT).fetchSemanticsNode().boundsInRoot.left
        val header = onNodeWithText("Worked", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val tools = onNodeWithText("Ran 2 commands", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue(abs(header - prose) < TOLERANCE_PX, "the run header is inset: $header vs the prose at $prose")
        assertTrue(abs(tools - prose) < TOLERANCE_PX, "the tool summary is inset: $tools vs the prose at $prose")
    }

    private companion object {
        const val TOLERANCE_PX = 0.5f
    }
}
