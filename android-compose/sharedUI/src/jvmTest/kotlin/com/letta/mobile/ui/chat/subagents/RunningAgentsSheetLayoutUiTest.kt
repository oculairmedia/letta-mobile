@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentActivitySnapshot
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.projectSubagentRoster
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The running-agents sheet: each running card is a hanging indent (the orb in its own column, the
 * title, meta, activity line and "View conversation" on one left edge after it), and a plan-less run
 * draws no ring over the timeline, so no progress indicator sits outside the card that owns it.
 * Checked at the four viewport widths, dark and light, with fake runs.
 */
class RunningAgentsSheetLayoutUiTest {
    private val now = 1_791_642_000_000L

    private val tasks = listOf(
        "Resume CI Maven cache fix" to "Bash Commit api-docs konan restore; check tests touching the dump",
        "Resume tool sweep revision fix" to "Bash Sum JUnit totals; start gate tasks",
        "Resume UI flake fixes" to "Bash Keep waiting for compile",
    )

    private val entries = tasks.mapIndexed { index, (title, line) ->
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
            activity = SubagentActivitySnapshot(lines = listOf(line)),
        )
    }

    @Test
    fun everyLineOfARunningCardSharesTheTitlesLeftEdge() = runComposeUiTest {
        var width by mutableStateOf(VIEWPORTS.first())
        var dark by mutableStateOf(true)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.width(width).height(SHEET_HEIGHT)) {
                    SubagentRosterList(projectSubagentRoster(entries, now), onOpenConversation = {}, onClearFinished = {})
                }
            }
        }
        val indent = with(density) { (LettaDimens.Space.md + LettaDimens.Orb.sm + LettaDimens.Space.md).toPx() }
        for (viewport in VIEWPORTS) {
            for (scheme in listOf(true, false)) {
                width = viewport
                dark = scheme
                waitForIdle()
                entries.forEachIndexed { index, entry ->
                    val key = entry.toolCallId
                    val label = "$viewport ${if (scheme) "dark" else "light"} $key"
                    val card = onNodeWithTag(SubagentRosterTag.RUNNING.of(key)).fetchSemanticsNode().boundsInRoot
                    val title = onNodeWithText(tasks[index].first, useUnmergedTree = true).left()
                    val meta = onNodeWithTag(SubagentRosterTag.SUBTITLE.of(key), useUnmergedTree = true).left()
                    val activity = onNodeWithText("› ${tasks[index].second}", useUnmergedTree = true).left()
                    val link = onNodeWithTag(SubagentRosterTag.OPEN.of(key), useUnmergedTree = true).left()
                    assertNear(card.left + indent, title, "$label: title sits after the orb column")
                    assertNear(title, meta, "$label: meta")
                    assertNear(title, activity, "$label: activity line")
                    assertNear(title, link, "$label: View conversation")
                }
            }
        }
    }

    @Test
    fun planLessRunsDrawNoProgressIndicatorOutsideTheirCards() = runComposeUiTest {
        var width by mutableStateOf(VIEWPORTS.first())
        var dark by mutableStateOf(true)
        val running = entries.map { it.toActiveSubagent() }.toImmutableList()
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.width(width).height(SHEET_HEIGHT)) {
                    // As the Android shared page draws them: the rings over the timeline, top end.
                    val rings = rememberSubagentRingsState(running)
                    SubagentRosterList(
                        projectSubagentRoster(entries, now),
                        onOpenConversation = {},
                        onClearFinished = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ActiveSubagentRings(rings.subagents, now = rings.now, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        for (viewport in VIEWPORTS) {
            for (scheme in listOf(true, false)) {
                width = viewport
                dark = scheme
                waitForIdle()
                onNodeWithTag(ActiveSubagentRingsTags.RINGS).assertDoesNotExist()
                val cards = entries.map { onNodeWithTag(SubagentRosterTag.RUNNING.of(it.toolCallId)).fetchSemanticsNode().boundsInRoot }
                val indicators = onAllNodes(HAS_PROGRESS, useUnmergedTree = true).fetchSemanticsNodes()
                indicators.forEach { node ->
                    val bounds = node.boundsInRoot
                    assertTrue(
                        cards.any { it.contains(bounds) },
                        "$viewport: a progress indicator at $bounds sits outside every running card",
                    )
                }
            }
        }
    }

    @Test
    fun aPlannedRunKeepsItsRingAndAPlanLessOneHasNone() = runComposeUiTest {
        val planned = entries[0].copy(todoProgress = com.letta.mobile.data.model.SubagentTodoProgressWire(completed = 1, total = 3))
        val snapshot = listOf(planned, entries[1], entries[2]).map { it.toActiveSubagent() }.toImmutableList()
        setContent {
            MaterialTheme {
                val rings = rememberSubagentRingsState(snapshot)
                ActiveSubagentRings(rings.subagents, now = rings.now)
            }
        }
        onNodeWithTag(ActiveSubagentRingsTags.ring(planned.toolCallId)).assertExists()
        assertEquals(1, onAllNodesWithTag(ActiveSubagentRingsTags.ring(planned.toolCallId)).fetchSemanticsNodes().size)
        onNodeWithTag(ActiveSubagentRingsTags.ring(entries[1].toolCallId)).assertDoesNotExist()
        onNodeWithTag(ActiveSubagentRingsTags.ring(entries[2].toolCallId)).assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.left(): Float = fetchSemanticsNode().boundsInRoot.left

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= PIXEL_TOLERANCE, "$message: expected x=$expected, was x=$actual")
    }

    private companion object {
        val VIEWPORTS: List<Dp> = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        val SHEET_HEIGHT: Dp = 900.dp

        /** Sub-pixel rounding between two laid-out nodes, nothing more. */
        const val PIXEL_TOLERANCE = 1f

        val HAS_PROGRESS = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    }
}
