@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-2ccpv: a canvas call (native, or the canvas CLI the timeline reads as one) is a step
 * line naming the canvas op, opening to its preview, with a chip that opens the canvas.
 */
class CanvasToolStepUiTest {
    private val compose = UiToolCall(
        name = "canvas_compose",
        arguments = """{"title":"Sprint plan","items":[{"kind":"NOTE","markdown":"Ship it"}]}""",
        result = """{"ok":true}""",
        status = "success",
        toolCallId = "call-1",
    )

    @Test
    fun aComposeStepNamesTheOpPreviewsItsItemsAndItsChipOpensTheCanvas() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme { ToolStep(compose, "call-1", rowCallbacks(host = ChatSurfaceHost(openCanvas = { opened++ }))) }
        }

        onNodeWithText("Composed canvas").assertExists()
        onNodeWithText("Sprint plan").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithText("NOTE  Ship it", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Open canvas").performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun withoutAHostCanvasTheStepShowsNoChip() = runComposeUiTest {
        setContent { MaterialTheme { ToolStep(compose, "call-1", rowCallbacks()) } }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithText("Open canvas").assertDoesNotExist()
    }
}
