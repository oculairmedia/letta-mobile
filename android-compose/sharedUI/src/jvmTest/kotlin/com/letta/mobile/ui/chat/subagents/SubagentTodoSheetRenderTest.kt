@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The subagent sheet at the review widths (360, 700, 760, 1200 dp) in Dark and Light, written to
 * build/subagent-sheet-render/ for review: a run with no todos (no todo section, the roster right
 * under the title) and a run with a plan (its checklist over the roster). Demo data only.
 */
class SubagentTodoSheetRenderTest {
    private val now = 1_791_642_000_000L

    private fun entry(id: String, description: String, status: String) = SubagentEntry(
        toolCallId = id,
        description = description,
        subagentType = "General-purpose",
        status = status,
        parentAgentId = "agent-parent",
        parentConversationId = "conv-parent",
        startedAt = (now - 90_000L).toString(),
    )

    private val roster = listOf(
        entry("a", "Tidy the release notes", "running"),
        entry("b", "Check the nightly build", "running"),
        entry("c", "Rename the settings keys", "success"),
        entry("d", "Draft the onboarding copy", "success"),
    )

    private val plan = SubagentTodoSheetState.Content(
        items = listOf(
            SubagentTodoItem("Read the release notes", SubagentTodoStatus.COMPLETED),
            SubagentTodoItem("Fix the headings", SubagentTodoStatus.IN_PROGRESS),
            SubagentTodoItem("Open the PR", SubagentTodoStatus.PENDING),
        ),
        progress = SubagentTodoProgress(completed = 1, total = 3),
    )

    @Test
    fun theSheetRendersWithAndWithoutTodosAtEveryReviewWidth() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) {
                render("no-todos-$width-${theme(dark)}.png", width, dark, SubagentTodoSheetState.Empty)
                render("todos-$width-${theme(dark)}.png", width, dark, plan)
            }
        }
    }

    private fun render(name: String, width: Int, dark: Boolean, state: SubagentTodoSheetState) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val touch = width < LettaDimens.Pane.wideBreakpoint.value
            setContent {
                SharedMaterialTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                            Surface(Modifier.fillMaxSize()) {
                                InspectorFrame(Modifier.size(width.dp, HEIGHT.dp)) {
                                    Box(Modifier.fillMaxSize())
                                    SubagentTodoSheet(
                                        description = "Tidy the release notes",
                                        state = state,
                                        onDismiss = {},
                                        roster = { SubagentRoster(entries = roster, onOpenConversation = null, now = { now }) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val file = File("build/subagent-sheet-render").apply { mkdirs() }.resolve(name)
            // On Touch the sheet is its own window over the page: capture it, the last root.
            val roots = onAllNodes(isRoot())
            val sheet = roots[roots.fetchSemanticsNodes().size - 1]
            ImageIO.write(sheet.captureToImage().toAwtImage(), "png", file)
        }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 720
    }
}
