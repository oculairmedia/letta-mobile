@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.subagents.EXTERNAL_AGENT_BADGE_TAG
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import kotlin.test.Test

/**
 * letta-mobile-zxk2n.8 (G40): a timeline row produced by an external coding agent names it - the
 * dispatch that started it and the report it sent back - and a Letta subagent's rows carry no badge.
 */
class ExternalAgentRowBadgeUiTest {
    private fun notification(agent: String?) = UiSubagentNotification(
        status = "failed",
        summary = "Refactor the parser",
        result = null,
        usage = null,
        transcriptUri = null,
        taskId = "task-1",
        subagentAgentId = agent,
    )

    @Test
    fun aClaudeCodeDispatchNamesItsAgent() = runComposeUiTest {
        val dispatch = UiSubagentDispatch(
            toolCallId = "call-cc",
            description = "Refactor the parser",
            subagentType = "claude-code",
            runInBackground = true,
            prompt = "Split the parser",
            subagentAgentId = "claude_0b1c2d3e-0000-4000-8000-000000000001",
        )
        val msg = message("m-1", "assistant", "").copy(
            toolCalls = listOf(
                UiToolCall(name = "Agent", arguments = "{}", result = null, status = "running", subagentDispatch = dispatch),
            ),
        )
        setContent { MaterialTheme { RenderRow(single(msg), rowContext(toolDetails = ChatToolDetails.Sheet)) } }

        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        awaitText("Dispatched: Refactor the parser")
        onNodeWithTag(EXTERNAL_AGENT_BADGE_TAG, useUnmergedTree = true).assertTextEquals("Claude Code")
    }

    @Test
    fun aCodexReportNamesItsAgent() = runComposeUiTest {
        setContent { MaterialTheme { SubagentNotificationCard(notification(agent = "codex_9f8e"), openSubagent = null) } }

        awaitText("Refactor the parser")
        onNodeWithTag(EXTERNAL_AGENT_BADGE_TAG, useUnmergedTree = true).assertTextEquals("Codex")
    }

    @Test
    fun aLettaSubagentsReportHasNoBadge() = runComposeUiTest {
        setContent { MaterialTheme { SubagentNotificationCard(notification(agent = "agent-local-b"), openSubagent = null) } }

        awaitText("Refactor the parser")
        onNodeWithTag(EXTERNAL_AGENT_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Claude Code").assertDoesNotExist()
    }
}
