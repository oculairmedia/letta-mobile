@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
        // All line: no chevron, nothing to open.
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertIsNotEnabled()
    }

    @Test
    fun aFailedStopStillOpensToWhatWentWrong() = runComposeUiTest {
        val arguments = """{"task_id":"bash_9"}"""
        val error = "Error: no task bash_9"
        val call = UiToolCall(name = "TaskStop", arguments = arguments, result = error, status = "error", toolCallId = "stop")
        setContent { MaterialTheme { ToolStep(call, "stop", rowCallbacks()) } }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertIsEnabled().performClick()
        onNodeWithText(error, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aTodoListOpensToAChecklistWithItsDoneStruckAndItsCurrentSpinning() = runComposeUiTest {
        val todos = """[{"content":"Read","status":"completed"},{"content":"Build","status":"in_progress"},""" +
            """{"content":"Ship","status":"pending"}]"""
        val arguments = """{"todos":$todos}"""
        val call = UiToolCall(name = "TodoWrite", arguments = arguments, result = "ok", status = "success", toolCallId = "todo")
        setContent { MaterialTheme { ToolStep(call, "todo", rowCallbacks()) } }
        onNodeWithText("todos · 1 of 3 done").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onAllNodesWithTag(ChatRowTestTags.TOOL_CHECKLIST_ITEM).assertCountEquals(3)
        onNodeWithContentDescription("Done: Read").assertExists()
        onNodeWithContentDescription("In progress: Build").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CHECKLIST_CURRENT, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aWatchedTaskSaysWatching() = runComposeUiTest {
        val call = UiToolCall(
            name = "TaskOutput",
            arguments = """{"task_id":"bash_3"}""",
            result = "<status>completed</status><output>done</output>",
            status = "success",
            toolCallId = "watch",
        )
        setContent { MaterialTheme { ToolStep(call, "watch", rowCallbacks()) } }
        onNodeWithText("Watching").assertExists()
        onNodeWithText("task bash_3 · completed").assertExists()
    }
}
