package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
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

    private fun text(value: String, next: Long?, type: String) =
        TimelineSemanticWindowResult.Text(value, next, 20, 8, 1, type)

    private fun missing(type: String) =
        TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField, type)

    @Test fun explicitReadReplacesWindowAndPreviousRestoresOffset() {
        val offsets = mutableListOf<Long>()
        compose.setContent {
            DeferredWindowControls("row") { field, offset ->
                if (field != TimelineSemanticField.Content) missing("assistant") else {
                    offsets += offset
                    text("window-$offset", if (offset == 0L) 10L else null, "assistant")
                }
            }
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
        compose.setContent {
            DeferredWindowControls("row") { field, offset ->
                if (field != TimelineSemanticField.Content) missing("assistant")
                else text("window-$offset", null, "assistant")
            }
        }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        // A single-page body still reads as a page, but neither direction is offered.
        compose.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous page").assertIsNotEnabled()
    }

    /** letta-mobile-jp78k: `content` on a tool call is `name(arguments)`, and is never what to show. */
    @Test fun aToolCallShowsItsOutputRatherThanItsSynthesizedContent() {
        compose.setContent {
            DeferredWindowControls("row") { field, _ ->
                when (field) {
                    TimelineSemanticField.toolName() -> text("Bash", null, "tool_call")
                    TimelineSemanticField.ToolReturnByCallId -> text("total 4 drwx", null, "tool_call")
                    else -> text("Bash({\\\"command\\\":\\\"ls\\\"})", null, "tool_call")
                }
            }
        }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("total 4 drwx").assertIsDisplayed()
    }

    /** A tool call still in flight has no return, so its arguments are the only truthful text. */
    @Test fun aToolCallWithoutAReturnFallsBackToItsArguments() {
        compose.setContent {
            DeferredWindowControls("row") { field, _ ->
                when (field) {
                    TimelineSemanticField.toolName() -> text("Bash", null, "tool_call")
                    TimelineSemanticField.toolArguments() -> text("{\"command\":\"ls\"}", null, "tool_call")
                    else -> missing("tool_call")
                }
            }
        }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("{\"command\":\"ls\"}").assertIsDisplayed()
    }

    @Test fun aBodyWithNoShowableStringSaysSoInsteadOfShowingNothing() {
        compose.setContent {
            DeferredWindowControls("row") { _, _ -> missing("assistant") }
        }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("This record stores no text to show.").assertIsDisplayed()
    }
}
