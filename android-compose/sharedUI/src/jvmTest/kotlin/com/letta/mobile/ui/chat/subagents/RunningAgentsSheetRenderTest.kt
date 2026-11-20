@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentActivitySnapshot
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The running-agents sheet with three running cards, and the chat page behind it with the subagent
 * rings as the Android shared page draws them, at the review widths (360, 700, 760, 1200 dp) in Dark
 * and Light, written to build/running-agents-render/ for review. Fake runs only.
 */
class RunningAgentsSheetRenderTest {
    private val now = 1_791_642_000_000L

    private val entries = listOf(
        "Resume CI Maven cache fix" to "Bash Commit api-docs konan restore; check tests touching the dump",
        "Resume tool sweep revision fix" to "Bash Sum JUnit totals; start gate tasks",
        "Resume UI flake fixes" to "Bash Keep waiting for compile",
    ).mapIndexed { index, (title, line) ->
        SubagentEntry(
            toolCallId = "toolu_$index",
            description = title,
            subagentType = "General-purpose",
            status = "running",
            parentAgentId = "agent-parent",
            parentConversationId = "conv-parent",
            subagentAgentId = "agent-child-$index",
            subagentConversationId = "conv-child-$index",
            startedAt = (now - 3_122_000L).toString(),
            lastActivityAtMs = now,
            toolCallCount = 44,
            model = "example/model-large",
            activity = SubagentActivitySnapshot(lines = listOf(line)),
        )
    }

    @Test
    fun theSheetAndThePageRenderAtEveryReviewWidth() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) {
                render("sheet-$width-${theme(dark)}.png", width, dark, page = false)
                render("page-$width-${theme(dark)}.png", width, dark, page = true)
            }
        }
    }

    private fun render(name: String, width: Int, dark: Boolean, page: Boolean) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val touch = width < LettaDimens.Pane.wideBreakpoint.value
            setContent {
                SharedMaterialTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                            Surface(Modifier.fillMaxSize()) {
                                InspectorFrame(Modifier.size(width.dp, HEIGHT.dp)) {
                                    if (page) Page() else Sheet()
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val roots = onAllNodes(isRoot())
            val target: SemanticsNodeInteraction = if (page) roots[0] else roots[roots.fetchSemanticsNodes().size - 1]
            val file = File("build/running-agents-render").apply { mkdirs() }.resolve(name)
            ImageIO.write(target.captureToImage().toAwtImage(), "png", file)
        }

    @androidx.compose.runtime.Composable
    private fun Page() {
        val rings = rememberSubagentRingsState(entries.map { it.toActiveSubagent() }.toImmutableList())
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
            ) {
                Text("ok", modifier = Modifier.align(Alignment.End))
                Text("Monitor  Current-head checks finished  2 events · watching", style = MaterialTheme.typography.labelMedium)
                Text("The checks are green; the remaining review threads are still with the builder.")
            }
            ActiveSubagentRings(
                subagents = rings.subagents,
                now = rings.now,
                modifier = Modifier.align(Alignment.TopEnd).padding(LettaDimens.Space.sm),
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Sheet() {
        Box(Modifier.fillMaxSize())
        SubagentTodoSheet(
            description = "Resume UI flake fixes",
            state = SubagentTodoSheetState.Empty,
            onDismiss = {},
            onViewConversation = {},
            roster = { SubagentRoster(entries = entries, onOpenConversation = {}, now = { now }) },
        )
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 720
    }
}
