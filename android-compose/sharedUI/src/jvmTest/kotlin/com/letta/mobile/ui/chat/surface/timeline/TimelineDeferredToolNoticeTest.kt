@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.timeline.TimelineSemanticField
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-032ia: a held-back tool call names the tool it belongs to, so the card never reads as
 * a nameless "message too large" for a row that plainly ran something.
 */
class TimelineDeferredToolNoticeTest {

    private fun text(value: String, type: String = "tool_call") =
        TimelineSemanticWindowResult.Text(value, null, 20, value.length, 1, type)

    private fun missing(type: String = "tool_call") =
        TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField, type)

    private fun ComposeUiTest.show(read: DeferredBodyRead) {
        setContent { MaterialTheme { TimelineDeferredBody("row", read) } }
    }

    @Test
    fun aHeldBackToolCallNamesItsTool() = runComposeUiTest {
        show { field, _ ->
            when (field) {
                TimelineSemanticField.toolName() -> text("Bash")
                TimelineSemanticField.ToolReturnByCallId -> text("y")
                else -> missing()
            }
        }
        onNodeWithText("Ran Bash · output too large to show inline").assertExists()
        onNodeWithText("View content").assertExists()
    }

    @Test
    fun aHeldBackMessageKeepsThePlainNotice() = runComposeUiTest {
        show { _, _ -> missing("assistant") }
        onNodeWithText("Message too large to show inline").assertExists()
    }

    @Test
    fun theNoticeIsComposedFromTheToolNameOnly() {
        assertEquals("Message too large to show inline", deferredNotice(null))
        assertEquals("Ran python3 · output too large to show inline", deferredNotice("python3"))
    }
}
