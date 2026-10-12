@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.timeline.TimelineSemanticField
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hard cut S9: the paged reader for a stored body too large to decode inline, ported from the
 * legacy Android list's DeferredWindowControls (its test moved here with it).
 */
class TimelineDeferredBodyTest {
    private fun text(value: String, next: Long? = null, type: String = "assistant") =
        TimelineSemanticWindowResult.Text(value, next, 20, 8, 1, type)

    private fun missing(type: String = "assistant") =
        TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField, type)

    private fun ComposeUiTest.show(read: DeferredBodyRead) {
        setContent { MaterialTheme { TimelineDeferredBody("row", read) } }
    }

    /** Mounts the card and opens it, which is the only way anything is ever read. */
    private fun ComposeUiTest.open(read: DeferredBodyRead) {
        show(read)
        onNodeWithText("View content").performClick()
    }

    /** A plain message: only `content` carries anything. */
    private fun contentOnly(body: (Long) -> TimelineSemanticWindowResult): DeferredBodyRead =
        { field, offset -> if (field == TimelineSemanticField.Content) body(offset) else missing() }

    @Test
    fun explicitReadReplacesWindowAndPreviousRestoresOffset() = runComposeUiTest {
        val offsets = mutableListOf<Long>()
        show(
            contentOnly { offset ->
                offsets += offset
                text("window-$offset", if (offset == 0L) 10L else null)
            },
        )
        runOnIdle { assertTrue(offsets.isEmpty()) }
        onNodeWithText("View content").performClick()
        onNodeWithText("window-0").assertIsDisplayed()
        // The page number is derived from history depth, so it always describes the offset shown.
        onNodeWithText("Page 1").assertIsDisplayed()
        onNodeWithContentDescription("Next page").performClick()
        onNodeWithText("window-10").assertIsDisplayed()
        onNodeWithText("Page 2").assertIsDisplayed()
        onNodeWithContentDescription("Previous page").performClick()
        onNodeWithText("window-0").assertIsDisplayed()
        onNodeWithText("Page 1").assertIsDisplayed()
        runOnIdle { assertEquals(listOf(0L, 10L, 0L), offsets) }
    }

    @Test
    fun theLastPageOffersNoNextAndTheFirstOffersNoPrevious() = runComposeUiTest {
        open(contentOnly { offset -> text("window-$offset") })
        onNodeWithText("window-0").assertIsDisplayed()
        // A single-page body still reads as a page, but neither direction is offered.
        onNodeWithContentDescription("Next page").assertIsNotEnabled()
        onNodeWithContentDescription("Previous page").assertIsNotEnabled()
    }

    /** letta-mobile-jp78k: `content` on a tool call is `name(arguments)`, and is never what to show. */
    @Test
    fun aToolCallShowsItsOutputRatherThanItsSynthesizedContent() = runComposeUiTest {
        open { field, _ ->
            when (field) {
                TimelineSemanticField.toolName() -> text("Bash", type = "tool_call")
                TimelineSemanticField.ToolReturnByCallId -> text("total 4 drwx", type = "tool_call")
                else -> text("""Bash({"command":"ls"})""", type = "tool_call")
            }
        }
        onNodeWithText("total 4 drwx").assertIsDisplayed()
    }

    /** A tool call still in flight has no return, so its arguments are the only truthful text. */
    @Test
    fun aToolCallWithoutAReturnFallsBackToItsArguments() = runComposeUiTest {
        open { field, _ ->
            when (field) {
                TimelineSemanticField.toolName() -> text("Bash", type = "tool_call")
                TimelineSemanticField.toolArguments() -> text("""{"command":"ls"}""", type = "tool_call")
                else -> missing("tool_call")
            }
        }
        onNodeWithText("""{"command":"ls"}""").assertIsDisplayed()
    }

    @Test
    fun aBodyWithNoShowableStringSaysSoInsteadOfShowingNothing() = runComposeUiTest {
        open { _, _ -> missing() }
        onNodeWithText("This record stores no text to show.").assertIsDisplayed()
    }

    @Test
    fun theOpeningAnchorPrefersTheSavedPositionThenTheRouteTarget() {
        assertEquals("m-1" to 42, openingAnchor("m-1" to 42, "target"))
        assertEquals("target" to 0, openingAnchor(null, "target"))
        assertEquals(null, openingAnchor(null, null))
    }
}
