@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws an opened run's step lines (a command that took a while, an edit with its line counts, a
 * failed command and a running one) in both themes at the phone, tablet and desktop widths, and
 * writes PNGs to build/tool-step-snapshots for review. It asserts only that it rendered.
 */
class ToolStepLineSnapshotTest {
    private val calls = listOf(
        UiToolCall(name = "Bash", arguments = """{"command":"./gradlew test"}""", result = "BUILD SUCCESSFUL", status = "success", toolCallId = "c1", executionTimeMs = 42_300L),
        UiToolCall(
            name = "Edit",
            arguments = """{"file_path":"src/commonMain/Composer.kt","old_string":"a\nb","new_string":"c\nd\ne"}""",
            result = "The file was updated",
            status = "success",
            toolCallId = "c2",
            executionTimeMs = 120L,
        ),
        UiToolCall(name = "Bash", arguments = """{"command":"cat missing.txt"}""", result = "No such file", status = "error", toolCallId = "c3"),
        UiToolCall(name = "Read", arguments = """{"file_path":"README.md"}""", result = null, status = null, toolCallId = "c4"),
    )

    private val run = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "t", role = "assistant", content = "", timestamp = "2026-11-22T16:00:02Z", runId = RUN_ID, toolCalls = calls),
        ).map { it to GroupPosition.None },
    )

    private fun snapshot(width: Dp, dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Box(Modifier.width(width).wrapContentHeight().background(MaterialTheme.colorScheme.background).testTag(FRAME)) {
                    RenderRow(run, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "t"))
                }
            }
        }
        waitForIdle()
        val image = onNodeWithTag(FRAME).captureToImage().toAwtImage()
        val out = File("build/tool-step-snapshots").apply { mkdirs() }
            .resolve("steps-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun stepLinesAtEveryWidth() = WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(width, it) } }

    private companion object {
        const val RUN_ID = "run-snapshot"
        const val FRAME = "tool-step-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 700
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
