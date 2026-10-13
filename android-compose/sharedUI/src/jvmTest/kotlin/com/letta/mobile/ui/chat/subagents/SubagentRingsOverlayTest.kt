@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.3.19: the subagent rings and their todo sheet are shared; desktop draws them
 * over its chat page from the conversation's registry entries.
 */
class SubagentRingsOverlayTest {
    private fun entry(id: String, type: String = "General-purpose", status: String = "running") = SubagentEntry(
        toolCallId = id,
        description = "work $id",
        subagentType = type,
        status = status,
    )

    @Test
    fun aRingOpensItsTodoSheetWithTheLoadedChecklistAndTheRoster() = runComposeUiTest {
        val loaded = mutableListOf<String>()
        val loader = SubagentTodoLoader { target ->
            loaded += target.toolCallId
            Result.success(
                listOf(
                    SubagentTodo(content = "Read the bead", status = "completed", activeForm = "Reading the bead"),
                    SubagentTodo(content = "Open the PR", status = "in_progress", activeForm = "Opening the PR"),
                ),
            )
        }
        setContent { MaterialTheme { SubagentRingsOverlay(listOf(entry("toolu_index")), loader) } }

        onNodeWithTag(ActiveSubagentRingsTags.ring("toolu_index")).performClick()
        waitForIdle()
        assertEquals(listOf("toolu_index"), loaded)
        onNodeWithTag(SubagentTodoSheetTags.SHEET).assertExists()
        onNodeWithText("1/2 done").assertExists()
        onNodeWithText("Opening the PR").assertExists()
        // The conversation's roster sits under the checklist.
        onNodeWithTag(SubagentRosterTag.RUNNING.of("toolu_index")).assertExists()
    }

    @Test
    fun reflectionRunsDrawNoRing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SubagentRingsOverlay(listOf(entry("subagent-reflect", type = "Reflection")), SubagentTodoLoader { Result.success(emptyList()) })
            }
        }
        onNodeWithTag(ActiveSubagentRingsTags.RINGS).assertDoesNotExist()
    }

    @Test
    fun registryEntriesMapToRingsTheSameOnEveryClient() {
        val running = entry("toolu_a").toActiveSubagent()
        assertEquals(ActiveSubagent.Status.RUNNING, running.status)
        assertEquals(ActiveSubagent.Kind.SUBAGENT, running.kind)
        val background = SubagentEntry(toolCallId = "", taskId = "task_9", description = "Build", subagentType = "Bash", status = "success").toActiveSubagent()
        assertEquals("task_9", background.id)
        assertEquals(ActiveSubagent.Kind.BACKGROUND_TASK, background.kind)
        assertEquals(ActiveSubagent.Status.COMPLETED, background.status)
        assertEquals(ActiveSubagent.Status.FAILED, "cancelled".toActiveSubagentStatus())
    }
}
