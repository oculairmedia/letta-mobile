@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.timeline.TimelineItemRow
import com.letta.mobile.ui.chat.surface.timeline.TimelineRowBindings
import com.letta.mobile.ui.chat.surface.timeline.TimelineRowContexts
import com.letta.mobile.ui.chat.surface.timeline.rememberRowSpacing
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rows that draw no content must take no space in either timeline list. The non-paged list removes
 * option-hidden messages before building rows; Paging owns the paged list's slots, so its empty
 * content must not retain leading padding at the shared [TimelineItemRow] seam. Empty and absorbed
 * rows use that same seam and must obey the same contract.
 *
 * The oracle is measurement, not production spacing helpers: rows are laid out at phone width on
 * the Touch idiom and the real text and summary-line bounds are read with
 * [getUnclippedBoundsInRoot]. Expected gaps are derived independently from the design tokens.
 */
class TimelineHiddenRowSpacingTest {
    @Test
    fun aHiddenReasoningRowTakesNoSpaceBetweenItsNeighbours() {
        val withHidden = measure(
            items = listOf(prose(1), hiddenReasoning(1), prose(2)),
            displayOptions = ChatDisplayOptions(showReasoning = false),
        )
        val control = measure(items = listOf(prose(1), prose(2)), displayOptions = ChatDisplayOptions())
        assertEquals(
            control.gap(PROSE_1, PROSE_2).rounded(),
            withHidden.gap(PROSE_1, PROSE_2).rounded(),
            "the hidden reasoning row must leave the visible rows' spacing as if it were absent",
        )
        assertGap(ChatRowSpacing.ungrouped, withHidden.rootGap(), "row roots across hidden reasoning")
    }

    @Test
    fun aHiddenCompactionRowOpensNoVoid() {
        val withHidden = measure(
            items = listOf(prose(1), hiddenCompaction(), prose(2)),
            displayOptions = ChatDisplayOptions(showCompactions = false),
        )
        val control = measure(items = listOf(prose(1), prose(2)), displayOptions = ChatDisplayOptions())
        val gap = withHidden.gap(PROSE_1, PROSE_2)
        assertTrue(
            gap <= MAX_VISIBLE_GAP_DP,
            "the hidden compaction row opened a void: $withHidden (bound $MAX_VISIBLE_GAP_DP, control $control)",
        )
        assertGap(ChatRowSpacing.ungrouped, withHidden.rootGap(), "row roots across hidden compaction")
    }

    @Test
    fun hiddenRowStacksNeverOpenASectionVoid() {
        val gaps = measure(
            items = listOf(prose(1), hiddenReasoning(1), hiddenReasoning(2), prose(2), hiddenCompaction(), prose(3)),
            displayOptions = ChatDisplayOptions(showReasoning = false, showCompactions = false),
        )
        val measured = listOf(gaps.gap(PROSE_1, PROSE_2), gaps.gap(PROSE_2, PROSE_3))
        val worst = measured.max()
        assertTrue(worst <= MAX_VISIBLE_GAP_DP, "stacked hidden rows opened a void of $worst (bound $MAX_VISIBLE_GAP_DP): $gaps")
        measured.forEach { gap -> assertTrue(gap <= MAX_VISIBLE_GAP_DP, "hidden stack ink gap $gap exceeds $MAX_VISIBLE_GAP_DP") }
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGap(0, 1), "first row-root gap across hidden rows")
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGap(1, 2), "second row-root gap across hidden rows")
    }

    @Test
    fun anEmptyAssistantRowTakesNoSpaceBetweenItsNeighbours() {
        val gaps = measure(items = listOf(prose(1), blankAssistant(), prose(2)), displayOptions = ChatDisplayOptions())
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGap(), "row roots across an empty assistant row")
    }

    @Test
    fun anAbsorbedRunTakesNoSpaceBetweenItsNeighbours() {
        val gaps = measure(
            items = listOf(prose(1), absorbedRun(), prose(2)),
            displayOptions = ChatDisplayOptions(showReasoning = false),
        )
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGap(), "row roots across an absorbed run")
    }

    @Test
    fun aMonitorWatchBetweenProseSpacesLikeTheRhythmPins() {
        val gaps = measure(items = listOf(prose(1), monitorWatch(), prose(2)), displayOptions = ChatDisplayOptions())
        val measured = listOf(gaps.gapAboveLine(), gaps.gapBelowLine())
        measured.forEach { beat ->
            assertTrue(beat <= MAX_VISIBLE_GAP_DP, "a void around the watch chip: $beat (bound $MAX_VISIBLE_GAP_DP); $gaps")
        }
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGapAboveLine(), "row root above the Monitor line")
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGapBelowLine(), "row root below the Monitor line")
    }

    @Test
    fun aWorkedSummaryKeepsTheSameSectionBeatAsAMonitorLine() {
        val gaps = measure(items = listOf(prose(1), workedRun(), prose(2)), displayOptions = ChatDisplayOptions())
        val monitor = measure(items = listOf(prose(1), monitorWatch(), prose(2)), displayOptions = ChatDisplayOptions())
        assertGap(PROSE_TO_NOTICE_GAP, gaps.gapAboveLine(), "prose above the Worked summary")
        // The section is 24 dp; the following text sits inside the row's 7 dp padding and
        // rendered markdown's measured inset (2 dp in this fixture). The earlier 31 dp oracle
        // omitted that rendered inset. Pin the token at row bounds and compare the visible gaps.
        assertGap(ChatRowSpacing.ungrouped, gaps.rootGapBelowLine(), "row root below the Worked summary")
        assertGap(monitor.gapBelowLine(), gaps.gapBelowLine(), "Worked and Monitor text gaps")
    }

    companion object {
        /** Existing regression bound: a section gap and its two prose insets never reach 48–60 dp. */
        private val MAX_VISIBLE_GAP_DP = 44.dp
        private val PROSE_TO_NOTICE_GAP = ChatRowSpacing.ungrouped + ChatRowSpacing.bubblePaddingVertical
        private val TOLERANCE = 0.5.dp

        private const val PROSE_1 = "Answer one."
        private const val PROSE_2 = "Answer two."
        private const val PROSE_3 = "Answer three."
        private const val WATCH_EVENT = "PR merged at da0dd261"
        private const val RUN_ID = "run-hidden-spacing"
        private const val ABSORBED_RUN_ID = "run-absorbed-spacing"

        private val proseTexts = mapOf(1 to PROSE_1, 2 to PROSE_2, 3 to PROSE_3)

        private fun prose(id: Int) = ChatRenderItem.Single(
            UiMessage(id = "prose-$id", role = "assistant", content = proseTexts.getValue(id), timestamp = "2026-11-26T10:0$id:00Z"),
            GroupPosition.First,
        )

        private fun hiddenReasoning(id: Int) = ChatRenderItem.Single(
            UiMessage(
                id = "think-$id", role = "assistant", content = "quiet thought $id",
                timestamp = "2026-11-26T10:0$id:30Z", isReasoning = true,
            ),
            GroupPosition.Middle,
        )

        private fun hiddenCompaction() = ChatRenderItem.Single(
            UiMessage(
                id = "summary", role = "system", content = "Earlier context folded",
                timestamp = "2026-11-26T10:01:45Z", isCompaction = true,
            ),
            GroupPosition.None,
        )

        private fun blankAssistant() = ChatRenderItem.Single(
            UiMessage(id = "blank", role = "assistant", content = "", timestamp = "2026-11-26T10:01:40Z"),
            GroupPosition.None,
        )

        /** A settled run whose only message is hidden: its plan draws neither a header nor a body. */
        private fun absorbedRun(): ChatRenderItem.RunBlock {
            val message = (hiddenReasoning(9) as ChatRenderItem.Single).message.copy(runId = ABSORBED_RUN_ID)
            return ChatRenderItem.RunBlock(ABSORBED_RUN_ID, listOf(message to GroupPosition.Middle))
        }

        private fun monitorWatch() = ChatRenderItem.Single(
            UiMessage(
                id = "watch", role = "assistant", content = "",
                timestamp = "2026-11-26T10:01:45Z",
                subagentNotification = UiSubagentNotification(
                    status = "running",
                    summary = "Monitor PR #2719",
                    result = "<event>$WATCH_EVENT</event>",
                    usage = "events: 6",
                    transcriptUri = null,
                    taskId = "monitor_abc",
                ),
            ),
            GroupPosition.None,
        )

        /** A settled one-tool run folds to its Worked summary line. */
        private fun workedRun(): ChatRenderItem.RunBlock {
            val call = UiToolCall(
                toolCallId = "call-spacing",
                name = "Bash",
                arguments = """{"command":"true"}""",
                result = "ok",
                status = "success",
            )
            val message = UiMessage(
                id = "worked", role = "assistant", content = "", timestamp = "2026-11-26T10:01:45Z",
                runId = RUN_ID, toolCalls = listOf(call),
            )
            return ChatRenderItem.RunBlock(RUN_ID, listOf(message to GroupPosition.None))
        }
    }

    private fun assertGap(expected: Dp, actual: Dp, label: String) {
        assertTrue(
            abs(actual.value - expected.value) <= TOLERANCE.value,
            "$label must follow the tokens: expected $expected, measured $actual",
        )
    }

    /** Lays rows through the seam used by both lists and returns their real ink and line bounds. */
    private fun measure(items: List<ChatRenderItem>, displayOptions: ChatDisplayOptions): Ink {
        var ink = Ink(emptyMap(), emptyMap())
        runDesktopComposeUiTest(width = 420, height = 1200) {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides LettaHost.Touch) {
                    MaterialTheme {
                        Column(Modifier.width(420.dp)) { TimelineItems(items, displayOptions) }
                    }
                }
            }
            proseTexts.values.filter { text -> items.anyVisibleProse(text) }.forEach { awaitText(it) }
            ink = Ink(
                proseTexts.values.mapNotNull { text -> textBounds(text)?.let { text to it } }.toMap(),
                noticeLineBounds(),
                onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT, useUnmergedTree = true).let { nodes ->
                    List(nodes.fetchSemanticsNodes().size) { nodes[it].getUnclippedBoundsInRoot() }.sortedBy { it.top }
                },
            )
        }
        return ink
    }

    private fun List<ChatRenderItem>.anyVisibleProse(text: String): Boolean =
        any { (it as? ChatRenderItem.Single)?.message?.content == text }

    @Composable
    private fun TimelineItems(items: List<ChatRenderItem>, displayOptions: ChatDisplayOptions) {
        val context = rowContext().copy(displayOptions = displayOptions)
        val bindings = TimelineRowBindings(TimelineRowContexts(context, streaming = null), rowCallbacks())
        items.forEachIndexed { index, item ->
            val spacing = rememberRowSpacing(item, items.getOrNull(index - 1), items.getOrNull(index + 1))
            TimelineItemRow(item, bindings, spacing = spacing)
        }
    }

    private fun ComposeUiTest.textBounds(text: String): DpRect? {
        val nodes = onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it].getUnclippedBoundsInRoot() }
            .minByOrNull { it.bottom - it.top }
    }

    private fun ComposeUiTest.noticeLineBounds(): Map<String, DpRect> =
        listOf(ChatRowTestTags.MONITOR_WATCHER_LINE, ChatRowTestTags.RUN_HEADER).mapNotNull { tag ->
            val nodes = onAllNodesWithTag(tag, useUnmergedTree = true)
            val bounds = List(nodes.fetchSemanticsNodes().size) { nodes[it].getUnclippedBoundsInRoot() }
            bounds.maxByOrNull { it.top }?.let { tag to it }
        }.toMap()

    private class Ink(val bounds: Map<String, DpRect>, val lines: Map<String, DpRect>, val roots: List<DpRect> = emptyList()) {
        fun rootGap(upper: Int = 0, lower: Int = 1): Dp = roots[lower].top - roots[upper].bottom
        fun rootGapAboveLine(): Dp = lines.values.first().top - roots.first().bottom
        fun rootGapBelowLine(): Dp = roots.last().top - lines.values.first().bottom
        fun gap(upper: String, lower: String): Dp = bounds.getValue(lower).top - bounds.getValue(upper).bottom

        fun gapAboveLine(): Dp {
            val line = lines.values.maxByOrNull { it.top } ?: return Dp.Infinity
            val upper = bounds.values.filter { it.bottom <= line.top }.maxByOrNull { it.bottom } ?: return Dp.Infinity
            return line.top - upper.bottom
        }

        fun gapBelowLine(): Dp {
            val line = lines.values.maxByOrNull { it.top } ?: return Dp.Infinity
            val lower = bounds.values.filter { it.top >= line.bottom }.minByOrNull { it.top } ?: return Dp.Infinity
            return lower.top - line.bottom
        }

        override fun toString(): String =
            bounds.entries.joinToString { "${it.key}: ${it.value}" } + " lines: " +
                lines.entries.joinToString { "${it.key}: ${it.value}" }
    }
}

private fun Dp.rounded(): Dp = Dp((this.value * 10).toInt() / 10f)
