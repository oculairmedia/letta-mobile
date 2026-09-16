package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.letta.mobile.feature.chat.screen.DeferredBodyRead
import com.letta.mobile.feature.chat.screen.DeferredWindowControls
import com.letta.mobile.data.timeline.TimelineSemanticField
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeferredWindowControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun text(value: String, next: Long? = null, type: String = "assistant") =
        TimelineSemanticWindowResult.Text(value, next, 20, 8, 1, type)

    private fun missing(type: String = "assistant") =
        TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField, type)

    /** Mounts the card and opens it, which is the only way anything is ever read. */
    private fun open(read: DeferredBodyRead) {
        compose.setContent { DeferredWindowControls("row", read) }
        compose.onNodeWithText("View content").performClick()
    }

    /** A plain message: only `content` carries anything. */
    private fun contentOnly(body: (Long) -> TimelineSemanticWindowResult): DeferredBodyRead =
        { field, offset -> if (field == TimelineSemanticField.Content) body(offset) else missing() }

    @Test fun explicitReadReplacesWindowAndPreviousRestoresOffset() {
        val offsets = mutableListOf<Long>()
        compose.setContent {
            DeferredWindowControls("row", contentOnly { offset ->
                offsets += offset
                text("window-$offset", if (offset == 0L) 10L else null)
            })
        }
        compose.runOnIdle { org.junit.Assert.assertTrue(offsets.isEmpty()) }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        // The page number is derived from history depth, so it always describes the offset shown.
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next page").performClick()
        compose.onNodeWithText("window-10").assertIsDisplayed()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous page").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf(0L, 10L, 0L), offsets) }
    }

    @Test fun theLastPageOffersNoNextAndTheFirstOffersNoPrevious() {
        open(contentOnly { offset -> text("window-$offset") })
        compose.onNodeWithText("window-0").assertIsDisplayed()
        // A single-page body still reads as a page, but neither direction is offered.
        compose.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous page").assertIsNotEnabled()
    }

    /** letta-mobile-jp78k: `content` on a tool call is `name(arguments)`, and is never what to show. */
    @Test fun aToolCallShowsItsOutputRatherThanItsSynthesizedContent() {
        open { field, _ ->
            when (field) {
                TimelineSemanticField.toolName() -> text("Bash", type = "tool_call")
                TimelineSemanticField.ToolReturnByCallId -> text("total 4 drwx", type = "tool_call")
                else -> text("""Bash({"command":"ls"})""", type = "tool_call")
            }
        }
        compose.onNodeWithText("total 4 drwx").assertIsDisplayed()
    }

    /** A tool call still in flight has no return, so its arguments are the only truthful text. */
    @Test fun aToolCallWithoutAReturnFallsBackToItsArguments() {
        open { field, _ ->
            when (field) {
                TimelineSemanticField.toolName() -> text("Bash", type = "tool_call")
                TimelineSemanticField.toolArguments() -> text("""{"command":"ls"}""", type = "tool_call")
                else -> missing("tool_call")
            }
        }
        compose.onNodeWithText("""{"command":"ls"}""").assertIsDisplayed()
    }

    @Test fun aBodyWithNoShowableStringSaysSoInsteadOfShowingNothing() {
        open { _, _ -> missing() }
        compose.onNodeWithText("This record stores no text to show.").assertIsDisplayed()
    }
}
