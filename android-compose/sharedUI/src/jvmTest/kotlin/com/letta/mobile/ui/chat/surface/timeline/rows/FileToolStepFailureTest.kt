@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test

/**
 * letta-mobile-2ccpv: a failed file tool call says so on its line (muted amber "failed") and opens to
 * what went wrong, whatever its presenter shows when it succeeds.
 */
class FileToolStepFailureTest {
    private fun failed(name: String, arguments: String) =
        UiToolCall(name = name, arguments = arguments, result = ERROR, status = "error", toolCallId = "call-$name")

    private fun showsItsFailure(call: UiToolCall) = runComposeUiTest {
        setContent { MaterialTheme { ToolStep(call, call.toolCallId.orEmpty(), rowCallbacks()) } }
        onNodeWithTag(ChatRowTestTags.TOOL_STEP_NOTE, useUnmergedTree = true).assertTextEquals("failed")
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithText(ERROR, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aFailedReadSaysSoAndShowsTheError() = showsItsFailure(failed("Read", """{"file_path":"src/gone.kt"}"""))

    @Test
    fun aFailedWriteSaysSoAndShowsTheError() = showsItsFailure(failed("Write", """{"file_path":"/ro/a.kt","content":"x"}"""))

    @Test
    fun aFailedEditSaysSoAndShowsTheError() =
        showsItsFailure(failed("Edit", """{"file_path":"a.kt","old_string":"a","new_string":"b"}"""))

    @Test
    fun aFailedGlobSaysSoAndShowsTheError() = showsItsFailure(failed("Glob", """{"pattern":"**/*.kt"}"""))

    @Test
    fun aFailedGrepSaysSoAndShowsTheError() = showsItsFailure(failed("Grep", """{"pattern":"TODO"}"""))

    private companion object {
        const val ERROR = "Error: ENOENT: no such file or directory"
    }
}
