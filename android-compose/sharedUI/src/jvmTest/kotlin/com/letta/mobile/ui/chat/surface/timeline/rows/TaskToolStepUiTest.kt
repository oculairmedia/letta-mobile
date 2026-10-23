@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test

/**
 * letta-mobile-2ccpv: plan-mode changes draw as thin dividers (leaving one opens to its plan), and a
 * stopped task reads "Stopped task bash_3".
 */
class TaskToolStepUiTest {
    @Test
    fun enteringPlanModeIsADivider() = runComposeUiTest {
        val call = UiToolCall(name = "EnterPlanMode", arguments = "{}", result = "Entered", status = "success", toolCallId = "enter")
        setContent { MaterialTheme { ToolStep(call, "enter", rowCallbacks()) } }
        onNodeWithTag(ChatRowTestTags.PLAN_MODE_DIVIDER).assertExists()
        onNodeWithText("Entered plan mode").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun leavingPlanModeIsADividerThatOpensToThePlan() = runComposeUiTest {
        val call = UiToolCall(
            name = "ExitPlanMode",
            arguments = """{"plan":"# Add the card\n1. Build it"}""",
            result = "User has approved your plan",
            status = "success",
            toolCallId = "exit",
        )
        setContent { MaterialTheme { ToolStep(call, "exit", rowCallbacks()) } }
        onNodeWithText("Left plan mode").assertExists()
        onNodeWithTag(ChatRowTestTags.PLAN_MODE_DIVIDER).performClick()
        onNodeWithText("1. Build it", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aStoppedTaskNamesTheTask() = runComposeUiTest {
        val arguments = """{"task_id":"bash_3"}"""
        val call = UiToolCall(name = "TaskStop", arguments = arguments, result = "Killed", status = "success", toolCallId = "stop")
        setContent { MaterialTheme { ToolStep(call, "stop", rowCallbacks()) } }
        onNodeWithText("Stopped").assertExists()
        onNodeWithText("task bash_3").assertExists()
    }
}
