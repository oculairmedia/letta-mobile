@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.TimelineSemanticField
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import com.letta.mobile.ui.chat.surface.timeline.DeferredBodyPages
import com.letta.mobile.ui.chat.surface.timeline.TimelineDeferredBodyTags
import com.letta.mobile.ui.components.LETTA_TOOL_CARD_LINE_TAG
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A loaded skill is one row that owns its instructions. The runtime sends them apart from the call's
 * result (which only says "Launching skill: …"): held inline, they are the card's contents; too
 * large to hold inline, the card opens to a pager over their stored body, read only when asked,
 * and nothing else stands beside the row ("Message too large to show inline" is for unrelated
 * messages only).
 */
class ChatRowSkillInstructionsUiTest {
    private val call = UiToolCall(
        name = "Skill",
        arguments = """{"skill":"release-notes"}""",
        result = "Launching skill: release-notes",
        status = "success",
        toolCallId = CALL_ID,
        settled = true,
    )

    private fun message(call: UiToolCall) = UiMessage(id = "skill", role = "assistant", content = "", timestamp = T0, toolCalls = listOf(call))

    private fun ComposeUiTest.show(call: UiToolCall, pages: (String) -> (@Composable () -> Unit)? = { null }) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalSkillInstructionPages provides pages) {
                MaterialTheme { RenderRow(single(message(call)), rowContext(), rowCallbacks()) }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openTheLine() {
        onNode(hasTestTag(LETTA_TOOL_CARD_LINE_TAG) and hasAnyAncestor(hasTestTag(ChatRowTestTags.SKILL_LOAD_CARD)), useUnmergedTree = true)
            .performClick()
        waitForIdle()
    }

    @Test
    fun instructionsHeldInlineAreTheSkillsContents() = runComposeUiTest {
        show(call.copy(skillInstructions = ENVELOPE))
        onNodeWithText("Keep sentences short.", useUnmergedTree = true).assertDoesNotExist()
        openTheLine()
        onNodeWithText("House style for release notes.", useUnmergedTree = true).assertExists()
        awaitText("Keep sentences short.")
        onNodeWithText("skill_content", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Launching skill", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anOversizedSkillIsOneRowWhoseInstructionsOpenOnDemand() = runComposeUiTest {
        val reads = mutableListOf<TimelineSemanticField>()
        show(call) { id ->
            if (id != CALL_ID) null else ({
                DeferredBodyPages(rowKey = "skill-$id", openLabel = "Show instructions", read = { field, _ ->
                    reads += field
                    if (field == TimelineSemanticField.Content) {
                        TimelineSemanticWindowResult.Text("# Release notes\nKeep sentences short.", null, 20, 8, 1, "user_message")
                    } else {
                        TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField, "user_message")
                    }
                })
            })
        }
        // One row by default: the skill's line, with no held-back message card beside it.
        assertEquals(1, onAllNodesWithTag(ChatRowTestTags.SKILL_LOAD_CARD, useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithTag(TimelineDeferredBodyTags.ROOT, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Message too large to show inline", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Show instructions", useUnmergedTree = true).assertDoesNotExist()

        openTheLine()
        onNodeWithText("Instructions", useUnmergedTree = true).assertExists()
        runOnIdle { assertTrue(reads.isEmpty(), "nothing is read until asked: $reads") }
        onNodeWithText("Show instructions", useUnmergedTree = true).performClick()
        awaitText("# Release notes\nKeep sentences short.")
        onNodeWithTag(TimelineDeferredBodyTags.ROOT, useUnmergedTree = true).assertDoesNotExist()
    }

    private companion object {
        const val CALL_ID = "skill-1"
        const val T0 = "2026-11-18T09:00:00Z"
        const val ENVELOPE = "<skill_content name=\"release-notes\">\n---\nname: release-notes\n" +
            "description: House style for release notes.\n---\n\n# Release notes\n\nKeep sentences short.\n</skill_content>"
    }
}
